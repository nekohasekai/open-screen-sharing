package rfb

import "github.com/nekohasekai/open-screen-sharing/internal/rfb/filecopy"

func (c *Client) FileCopy() *filecopy.Manager {
	c.access.Lock()
	defer c.access.Unlock()
	if c.fileCopy == nil {
		c.fileCopy = filecopy.NewManager(c, filecopy.Capabilities{
			FileCopy:    c.info.Accepts(filecopy.MessageType),
			ObserveOnly: c.info.Flags&ServerFlagObserveOnly != 0,
		})
	}
	return c.fileCopy
}

func (c *Client) SetDownloading(active bool) error {
	c.access.Lock()
	changed := c.downloading != active && c.pushing && !c.mediaStream
	c.downloading = active
	c.access.Unlock()
	if !changed {
		return nil
	}
	width, height := c.FramebufferSize()
	arm := c.armMessage(width, height)
	if arm != nil {
		err := c.send(arm)
		if err != nil {
			return err
		}
	}
	return c.send(framebufferUpdateRequestMessage(true, width, height))
}
