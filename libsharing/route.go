package libsharing

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"io"
	"math/rand/v2"
	"net"
	"net/http"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf16"

	"github.com/sagernet/sing/common"
	"golang.org/x/crypto/ssh"
	"golang.org/x/net/dns/dnsmessage"
)

const (
	sshDefaultPort     = 22
	sshDialTimeout     = 3 * time.Second
	sshCommandTimeout  = 6 * time.Second
	suggestTimeout     = 8 * time.Second
	quad100Address     = "100.100.100.100"
	quad100HTTPTimeout = 1500 * time.Millisecond
	quad100DNSTimeout  = 1500 * time.Millisecond
	magicDNSSuffixMax  = 16
)

var (
	tailscaleIPv4Prefix = netip.MustParsePrefix("100.64.0.0/10")
	tailscaleIPv6Prefix = netip.MustParsePrefix("fd7a:115c:a1e0::/48")
	quad100Addr         = netip.MustParseAddr(quad100Address)
)

const unixTailscaleProbeScript = `
if [ -L /Library/Tailscale/ipnport ]; then
	port=$(readlink /Library/Tailscale/ipnport 2>/dev/null)
	token=$(cat "/Library/Tailscale/sameuserproof-$port" 2>/dev/null)
	if [ -n "$port" ] && [ -n "$token" ]; then
		out=$(curl -fsS -m 2 -u ":$token" "http://127.0.0.1:$port/localapi/v0/status?peers=false" 2>/dev/null)
		if [ -n "$out" ]; then
			printf '%s\n' "$out"
			exit 0
		fi
	fi
fi
for bin in \
	/Applications/Tailscale.app/Contents/MacOS/Tailscale \
	/opt/homebrew/bin/tailscale \
	/usr/local/bin/tailscale \
	/usr/bin/tailscale \
	/snap/bin/tailscale \
	/run/current-system/sw/bin/tailscale \
	tailscale
do
	if command -v "$bin" >/dev/null 2>&1 || [ -x "$bin" ]; then
		out=$(TAILSCALE_BE_CLI=true TERM=dumb "$bin" status --json --peers=false 2>/dev/null)
		if [ -n "$out" ]; then
			printf '%s\n' "$out"
			exit 0
		fi
	fi
done
`

const windowsTailscaleProbeScript = `
$bin = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
if (Test-Path $bin) {
	& $bin status --json --peers=false 2>$null
}
`

type RouteProbe struct {
	Index int32
	Probe *ServerProbe
}

func (r *ProbeRequest) ResolveRoute(remoteProtocol int32, targets string, trustedFingerprint string) *RouteProbe {
	lines := common.FilterNotDefault(strings.Split(targets, "\n"))
	if len(lines) == 0 {
		return &RouteProbe{Probe: &ServerProbe{Protocol: -1, ErrorCode: ErrorHostNotFound}}
	}
	type candidate struct {
		index int
		host  string
		port  int32
	}
	var candidates []candidate
	for index, line := range lines {
		host, portString, err := net.SplitHostPort(line)
		if err != nil {
			continue
		}
		port, err := strconv.Atoi(portString)
		if err != nil || port <= 0 || port > 65535 {
			continue
		}
		candidates = append(candidates, candidate{index: index, host: host, port: int32(port)})
	}
	if len(candidates) == 0 {
		return &RouteProbe{Probe: &ServerProbe{Protocol: -1, ErrorCode: ErrorHostNotFound}}
	}

	ctx, cancel := context.WithCancel(r.ctx)
	defer cancel()

	type outcome struct {
		index int
		probe *ServerProbe
	}
	results := make(chan outcome, len(candidates))
	for _, entry := range candidates {
		go func(entry candidate) {
			var probe *ServerProbe
			if remoteProtocol == ProtocolRDP {
				if r.CheckOwnScreen {
					if err := checkOwnScreen(ctx, entry.host, entry.port); err != nil {
						results <- outcome{index: entry.index, probe: failedProbe(entry.port, err)}
						return
					}
				}
				probe = r.probeRDP(ctx, entry.host, entry.port, false)
			} else {
				probe = r.probeServer(ctx, entry.host, entry.port)
			}
			results <- outcome{index: entry.index, probe: probe}
		}(entry)
	}

	var untrustedMatch *outcome
	var failures []outcome
	for range len(candidates) {
		select {
		case result := <-results:
			if result.probe.ErrorCode == ErrorNone {
				if trustedFingerprint == "" || result.probe.Fingerprint == "" || strings.EqualFold(result.probe.Fingerprint, trustedFingerprint) {
					return &RouteProbe{Index: int32(result.index), Probe: result.probe}
				}
				if untrustedMatch == nil {
					copied := *result.probe
					copied.ErrorCode = ErrorCertificateChanged
					untrustedMatch = &outcome{index: result.index, probe: &copied}
				}
				continue
			}
			failures = append(failures, result)
		case <-r.ctx.Done():
			return &RouteProbe{Probe: failedProbe(candidates[0].port, r.ctx.Err())}
		}
	}
	if untrustedMatch != nil {
		return &RouteProbe{Index: int32(untrustedMatch.index), Probe: untrustedMatch.probe}
	}
	common.SortBy(failures, func(item outcome) int { return item.index })
	for _, code := range []int32{ErrorOwnScreen, ErrorCertificateChanged, ErrorUnsupportedServer} {
		if index := slices.IndexFunc(failures, func(item outcome) bool { return item.probe.ErrorCode == code }); index >= 0 {
			return &RouteProbe{Index: int32(failures[index].index), Probe: failures[index].probe}
		}
	}
	if index := slices.IndexFunc(failures, func(item outcome) bool {
		return item.probe.ErrorCode != ErrorNetwork && item.probe.ErrorCode != ErrorHostNotFound
	}); index >= 0 {
		return &RouteProbe{Index: int32(failures[index].index), Probe: failures[index].probe}
	}
	return &RouteProbe{Index: int32(failures[0].index), Probe: failures[0].probe}
}

func (r *ProbeRequest) SuggestTailscaleAddresses(
	remoteProtocol int32,
	defaultPort int32,
	name string,
	existingHosts string,
	trustedFingerprint string,
	sshHost string,
	username string,
	password string,
) string {
	ctx, cancel := context.WithTimeout(r.ctx, suggestTimeout)
	defer cancel()

	existing := common.FilterNotDefault(strings.Split(existingHosts, "\n"))
	existingNormalized := common.Map(existing, normalizeHost)
	existingIPs := make(map[netip.Addr]bool)
	for _, host := range existing {
		if addr, err := netip.ParseAddr(strings.Trim(host, "[]")); err == nil {
			existingIPs[addr.Unmap()] = true
		} else if isTailscaleDomain(host) {
			if addr, ok := lookupQuad100A(ctx, normalizeHost(host)); ok {
				existingIPs[addr.Unmap()] = true
			}
		}
	}

	var (
		access            sync.Mutex
		discovered        []string
		discoveredIPIndex = make(map[netip.Addr]int)
		waitGroup         sync.WaitGroup
	)
	record := func(host string, ips []netip.Addr) {
		normalized := normalizeHost(host)
		if normalized == "" || slices.Contains(existingNormalized, normalized) {
			return
		}
		for _, ip := range ips {
			if existingIPs[ip.Unmap()] {
				return
			}
		}
		access.Lock()
		defer access.Unlock()
		if slices.ContainsFunc(discovered, func(saved string) bool { return normalizeHost(saved) == normalized }) {
			return
		}
		for _, ip := range ips {
			if existingIndex, found := discoveredIPIndex[ip.Unmap()]; found {
				if _, err := netip.ParseAddr(strings.Trim(discovered[existingIndex], "[]")); err == nil {
					if _, newErr := netip.ParseAddr(strings.Trim(host, "[]")); newErr != nil {
						discovered[existingIndex] = host
					}
				}
				return
			}
		}
		index := len(discovered)
		discovered = append(discovered, host)
		for _, ip := range ips {
			discoveredIPIndex[ip.Unmap()] = index
		}
	}

	if sshHost != "" && username != "" && password != "" {
		waitGroup.Add(1)
		go func() {
			defer waitGroup.Done()
			host, ips := probeTailscaleOverSSH(ctx, sshHost, username, password)
			if host != "" {
				record(host, ips)
			}
		}()
	}

	waitGroup.Add(1)
	go func() {
		defer waitGroup.Done()
		host, ip := r.probeTailscaleViaMagicDNS(ctx, remoteProtocol, defaultPort, name, existing, trustedFingerprint)
		if host != "" {
			var ips []netip.Addr
			if ip.IsValid() {
				ips = []netip.Addr{ip}
			}
			record(host, ips)
		}
	}()

	waitGroup.Wait()
	return strings.Join(discovered, "\n")
}

type tailscaleStatusJSON struct {
	BackendState   string `json:"BackendState"`
	CurrentTailnet *struct {
		MagicDNSEnabled bool `json:"MagicDNSEnabled"`
	} `json:"CurrentTailnet"`
	Self *struct {
		DNSName      string   `json:"DNSName"`
		TailscaleIPs []string `json:"TailscaleIPs"`
	} `json:"Self"`
}

func probeTailscaleOverSSH(ctx context.Context, host, username, password string) (string, []netip.Addr) {
	dialCtx, cancel := context.WithTimeout(ctx, sshDialTimeout)
	defer cancel()
	address := net.JoinHostPort(host, strconv.Itoa(sshDefaultPort))
	conn, err := new(net.Dialer).DialContext(dialCtx, "tcp", address)
	if err != nil {
		return "", nil
	}
	sshCtx, sshCancel := context.WithTimeout(ctx, sshCommandTimeout)
	defer sshCancel()
	stop := context.AfterFunc(sshCtx, func() { _ = conn.Close() })
	defer func() {
		stop()
		_ = conn.Close()
	}()
	config := &ssh.ClientConfig{
		User: username,
		Auth: []ssh.AuthMethod{
			ssh.Password(password),
			ssh.KeyboardInteractive(func(_, _ string, questions []string, _ []bool) ([]string, error) {
				if len(questions) != 1 {
					return nil, nil
				}
				return []string{password}, nil
			}),
		},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         sshDialTimeout,
	}
	sshConn, channels, requests, err := ssh.NewClientConn(conn, address, config)
	if err != nil {
		return "", nil
	}
	client := ssh.NewClient(sshConn, channels, requests)
	defer client.Close()
	session, err := client.NewSession()
	if err != nil {
		return "", nil
	}
	defer session.Close()

	var stdout bytes.Buffer
	session.Stdout = &stdout
	if strings.Contains(strings.ToLower(string(sshConn.ServerVersion())), "windows") {
		err = session.Run("powershell -NoProfile -NonInteractive -EncodedCommand " + encodePowerShellCommand(windowsTailscaleProbeScript))
	} else {
		session.Stdin = strings.NewReader(unixTailscaleProbeScript)
		err = session.Run("/bin/sh -s")
	}
	if err != nil && stdout.Len() == 0 {
		return "", nil
	}
	return parseTailscaleStatusOutput(stdout.Bytes())
}

func encodePowerShellCommand(script string) string {
	encoded := utf16.Encode([]rune(script))
	raw := make([]byte, len(encoded)*2)
	for index, unit := range encoded {
		binary.LittleEndian.PutUint16(raw[index*2:], unit)
	}
	return base64.StdEncoding.EncodeToString(raw)
}

func parseTailscaleStatusOutput(output []byte) (string, []netip.Addr) {
	start := bytes.IndexByte(output, '{')
	end := bytes.LastIndexByte(output, '}')
	if start < 0 || end <= start {
		return "", nil
	}
	var status tailscaleStatusJSON
	if json.Unmarshal(output[start:end+1], &status) != nil || status.Self == nil {
		return "", nil
	}
	if status.BackendState != "" && status.BackendState != "Running" {
		return "", nil
	}
	var ips []netip.Addr
	var ipv4 string
	for _, raw := range status.Self.TailscaleIPs {
		addr, err := netip.ParseAddr(strings.TrimSpace(raw))
		if err != nil {
			continue
		}
		addr = addr.Unmap()
		if tailscaleIPv4Prefix.Contains(addr) && addr != quad100Addr {
			ips = append(ips, addr)
			if ipv4 == "" {
				ipv4 = addr.String()
			}
		} else if tailscaleIPv6Prefix.Contains(addr) {
			ips = append(ips, addr)
		}
	}
	dnsName := strings.TrimSuffix(strings.TrimSpace(status.Self.DNSName), ".")
	if status.CurrentTailnet != nil && status.CurrentTailnet.MagicDNSEnabled && dnsName != "" {
		return dnsName, ips
	}
	return ipv4, ips
}

func (r *ProbeRequest) probeTailscaleViaMagicDNS(
	ctx context.Context,
	protocol int32,
	defaultPort int32,
	name string,
	existingHosts []string,
	trustedFingerprint string,
) (string, netip.Addr) {
	suffix := discoverTailnetSuffix(ctx)
	if suffix == "" {
		return "", netip.Addr{}
	}
	var stems []string
	addStem := func(raw string) {
		stem := sanitizeTailscaleHostname(raw)
		if stem == "" {
			return
		}
		if !slices.Contains(stems, stem) {
			stems = append(stems, stem)
		}
		if dash := strings.LastIndexByte(stem, '-'); dash > 0 {
			if _, err := strconv.Atoi(stem[dash+1:]); err == nil {
				base := stem[:dash]
				if base != "" && !slices.Contains(stems, base) {
					stems = append(stems, base)
				}
			}
		}
	}
	addStem(name)
	for _, host := range existingHosts {
		trimmed := strings.TrimSuffix(strings.TrimSuffix(host, "."), "._rfb._tcp.local")
		trimmed = strings.TrimSuffix(trimmed, ".local")
		if dot := strings.IndexByte(trimmed, '.'); dot > 0 && net.ParseIP(trimmed) == nil {
			trimmed = trimmed[:dot]
		}
		if net.ParseIP(strings.Trim(trimmed, "[]")) == nil {
			addStem(trimmed)
		}
	}
	if len(stems) == 0 {
		return "", netip.Addr{}
	}

	fingerprint := trustedFingerprint
	if fingerprint == "" && len(existingHosts) > 0 {
		var probe *ServerProbe
		if protocol == ProtocolRDP {
			probe = r.probeRDP(ctx, existingHosts[0], defaultPort, false)
		} else {
			probe = r.probeServer(ctx, existingHosts[0], defaultPort)
		}
		fingerprint = probe.Fingerprint
	}
	if fingerprint == "" {
		return "", netip.Addr{}
	}

	var fqdns []string
	for _, stem := range stems {
		fqdns = append(fqdns, stem+"."+suffix)
		for index := 1; index <= magicDNSSuffixMax; index++ {
			fqdns = append(fqdns, stem+"-"+strconv.Itoa(index)+"."+suffix)
		}
	}

	type resolvedCandidate struct {
		fqdn string
		addr netip.Addr
	}
	resolvedChannel := make(chan resolvedCandidate, len(fqdns))
	var dnsGroup sync.WaitGroup
	for _, fqdn := range fqdns {
		dnsGroup.Add(1)
		go func(fqdn string) {
			defer dnsGroup.Done()
			if addr, ok := lookupQuad100A(ctx, fqdn); ok {
				resolvedChannel <- resolvedCandidate{fqdn: fqdn, addr: addr}
			}
		}(fqdn)
	}
	dnsGroup.Wait()
	close(resolvedChannel)

	var candidates []resolvedCandidate
	for item := range resolvedChannel {
		candidates = append(candidates, item)
	}
	if len(candidates) == 0 {
		return "", netip.Addr{}
	}

	probeCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	matched := make(chan resolvedCandidate, len(candidates))
	var probeGroup sync.WaitGroup
	for _, item := range candidates {
		probeGroup.Add(1)
		go func(item resolvedCandidate) {
			defer probeGroup.Done()
			var probe *ServerProbe
			if protocol == ProtocolRDP {
				probe = r.probeRDP(probeCtx, item.addr.String(), defaultPort, false)
			} else {
				probe = r.probeServer(probeCtx, item.addr.String(), defaultPort)
			}
			if probe.ErrorCode == ErrorNone && probe.Fingerprint != "" && strings.EqualFold(probe.Fingerprint, fingerprint) {
				select {
				case matched <- item:
					cancel()
				default:
				}
			}
		}(item)
	}
	probeGroup.Wait()
	close(matched)
	if winner, ok := <-matched; ok {
		return winner.fqdn, winner.addr
	}
	return "", netip.Addr{}
}

func discoverTailnetSuffix(ctx context.Context) string {
	selfIP := queryQuad100SelfIP(ctx)
	if !selfIP.IsValid() {
		return ""
	}
	octets := selfIP.As4()
	arpa := strconv.Itoa(int(octets[3])) + "." + strconv.Itoa(int(octets[2])) + "." +
		strconv.Itoa(int(octets[1])) + "." + strconv.Itoa(int(octets[0])) + ".in-addr.arpa."
	ptr := lookupQuad100PTR(ctx, arpa)
	ptr = strings.TrimSuffix(ptr, ".")
	_, suffix, ok := strings.Cut(ptr, ".")
	if !ok || (!strings.HasSuffix(suffix, ".ts.net") && !strings.HasSuffix(suffix, ".tailscale.net")) {
		return ""
	}
	return suffix
}

func queryQuad100SelfIP(ctx context.Context) netip.Addr {
	if conn, err := net.Dial("udp4", net.JoinHostPort(quad100Address, "53")); err == nil {
		localAddr, _ := conn.LocalAddr().(*net.UDPAddr)
		_ = conn.Close()
		if localAddr != nil {
			if addr, ok := netip.AddrFromSlice(localAddr.IP); ok {
				addr = addr.Unmap()
				if addr.Is4() && tailscaleIPv4Prefix.Contains(addr) && addr != quad100Addr {
					return addr
				}
			}
		}
	}
	if addresses, err := net.InterfaceAddrs(); err == nil {
		for _, item := range addresses {
			ipNet, ok := item.(*net.IPNet)
			if !ok {
				continue
			}
			if addr, ok := netip.AddrFromSlice(ipNet.IP); ok {
				addr = addr.Unmap()
				if addr.Is4() && tailscaleIPv4Prefix.Contains(addr) && addr != quad100Addr {
					return addr
				}
			}
		}
	}
	httpCtx, cancel := context.WithTimeout(ctx, quad100HTTPTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(httpCtx, http.MethodGet, "http://"+quad100Address+"/", nil)
	if err != nil {
		return netip.Addr{}
	}
	resp, err := (&http.Client{Timeout: quad100HTTPTimeout}).Do(req)
	if err != nil {
		return netip.Addr{}
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return netip.Addr{}
	}
	text := string(body)
	for {
		start := strings.Index(text, "<li>")
		if start < 0 {
			break
		}
		text = text[start+4:]
		end := strings.Index(text, "</li>")
		if end < 0 {
			break
		}
		if addr, err := netip.ParseAddr(strings.TrimSpace(text[:end])); err == nil && addr.Is4() && tailscaleIPv4Prefix.Contains(addr) {
			return addr
		}
		text = text[end+5:]
	}
	return netip.Addr{}
}

func lookupQuad100PTR(ctx context.Context, arpa string) string {
	msg, ok := exchangeQuad100DNS(ctx, arpa, dnsmessage.TypePTR)
	if !ok {
		return ""
	}
	for _, answer := range msg.Answers {
		if ptr, isPTR := answer.Body.(*dnsmessage.PTRResource); isPTR {
			return ptr.PTR.String()
		}
	}
	return ""
}

func lookupQuad100A(ctx context.Context, fqdn string) (netip.Addr, bool) {
	if !strings.HasSuffix(fqdn, ".") {
		fqdn += "."
	}
	msg, ok := exchangeQuad100DNS(ctx, fqdn, dnsmessage.TypeA)
	if !ok {
		return netip.Addr{}, false
	}
	for _, answer := range msg.Answers {
		if a, isA := answer.Body.(*dnsmessage.AResource); isA {
			addr := netip.AddrFrom4(a.A)
			if tailscaleIPv4Prefix.Contains(addr) && addr != quad100Addr {
				return addr, true
			}
		}
	}
	return netip.Addr{}, false
}

func exchangeQuad100DNS(ctx context.Context, qname string, qtype dnsmessage.Type) (dnsmessage.Message, bool) {
	name, err := dnsmessage.NewName(qname)
	if err != nil {
		return dnsmessage.Message{}, false
	}
	id := uint16(rand.Uint32())
	builder := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: id, RecursionDesired: true})
	builder.EnableCompression()
	if builder.StartQuestions() != nil || builder.Question(dnsmessage.Question{
		Name:  name,
		Type:  qtype,
		Class: dnsmessage.ClassINET,
	}) != nil {
		return dnsmessage.Message{}, false
	}
	packet, err := builder.Finish()
	if err != nil {
		return dnsmessage.Message{}, false
	}
	dnsCtx, cancel := context.WithTimeout(ctx, quad100DNSTimeout)
	defer cancel()
	conn, err := new(net.Dialer).DialContext(dnsCtx, "udp", net.JoinHostPort(quad100Address, "53"))
	if err != nil {
		return dnsmessage.Message{}, false
	}
	defer conn.Close()
	if deadline, ok := dnsCtx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	if _, err := conn.Write(packet); err != nil {
		return dnsmessage.Message{}, false
	}
	var reply [1500]byte
	n, err := conn.Read(reply[:])
	if err != nil {
		return dnsmessage.Message{}, false
	}
	var parsed dnsmessage.Message
	if parsed.Unpack(reply[:n]) != nil || parsed.ID != id || !parsed.Authoritative || parsed.RCode != dnsmessage.RCodeSuccess {
		return dnsmessage.Message{}, false
	}
	return parsed, true
}

func sanitizeTailscaleHostname(value string) string {
	const maxLabelLength = 63
	var builder strings.Builder
	hyphenPending := false
	for _, r := range strings.ToLower(value) {
		switch {
		case (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9'):
			if hyphenPending && builder.Len() > 0 {
				if builder.Len()+2 > maxLabelLength {
					return builder.String()
				}
				builder.WriteByte('-')
			}
			hyphenPending = false
			builder.WriteRune(r)
			if builder.Len() >= maxLabelLength {
				return builder.String()
			}
		case r == '-' || r == ' ' || r == '_' || r == '.':
			hyphenPending = true
		}
	}
	return builder.String()
}

func normalizeHost(host string) string {
	return strings.TrimSuffix(strings.ToLower(strings.Trim(strings.TrimSpace(host), "[]")), ".")
}

func isTailscaleDomain(host string) bool {
	normalized := normalizeHost(host)
	return strings.HasSuffix(normalized, ".ts.net") || strings.HasSuffix(normalized, ".tailscale.net")
}

func resolveDialHost(ctx context.Context, host string) string {
	if !isTailscaleDomain(host) {
		return host
	}
	lookupCtx, cancel := context.WithTimeout(ctx, quad100DNSTimeout)
	defer cancel()
	if addrs, err := net.DefaultResolver.LookupHost(lookupCtx, host); err == nil && len(addrs) > 0 {
		return host
	}
	if addr, ok := lookupQuad100A(ctx, normalizeHost(host)); ok {
		return addr.String()
	}
	return host
}
