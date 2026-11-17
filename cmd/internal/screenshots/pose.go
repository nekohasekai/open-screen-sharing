package main

import (
	"os/exec"
	"sync"
)

func setPose(udid, pose string) error {
	helper, err := poseHelper()
	if err != nil {
		return err
	}
	return run(exec.Command("xcrun", "simctl", "spawn", udid, helper, pose))
}

var poseHelper = sync.OnceValues(func() (string, error) {
	return buildHelper("pose", "iphonesimulator", poseSource, "-mios-simulator-version-min=15.0", "-framework", "Foundation", "-framework", "IOKit")
})

// CoreSimulator's hinge control consumes vendor-defined HID events with a serialized
// VirtualMachines payload, usage page 0xff61, usage 0x5b and angles in degrees.
const poseSource = `
#import <Foundation/Foundation.h>
#import <mach/mach_time.h>

extern CFDataRef IOCFSerialize(CFTypeRef object, CFOptionFlags options);
extern CFTypeRef IOHIDEventCreateVendorDefinedEvent(CFAllocatorRef allocator,
    uint64_t timestamp, uint32_t usagePage, uint32_t usage, uint32_t version,
    const uint8_t *data, CFIndex length, uint32_t options);
extern CFTypeRef IOHIDEventSystemClientCreate(CFAllocatorRef allocator);
extern void IOHIDEventSystemClientDispatchEvent(CFTypeRef client, CFTypeRef event);

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        NSNumber *angle = argc == 2 ? (@{@"open": @180, @"closed": @0})[@(argv[1])] : nil;
        if (!angle) {
            fprintf(stderr, "Expected pose open or closed\n");
            return 2;
        }
        CFTypeRef client = IOHIDEventSystemClientCreate(kCFAllocatorDefault);
        if (!client) {
            fprintf(stderr, "Unable to create simulator HID client\n");
            return 1;
        }
        NSDictionary *payload = @{
            @"provider": @"com.apple.Virtualization.VirtualMachines",
            @"source": @"hinge-slider-control", @"type": @"range", @"value": angle,
        };
        CFDataRef data = IOCFSerialize((__bridge CFTypeRef)payload, 1);
        CFTypeRef event = data ? IOHIDEventCreateVendorDefinedEvent(kCFAllocatorDefault,
            mach_absolute_time(), 0xff61, 0x5b, 0,
            CFDataGetBytePtr(data), CFDataGetLength(data), 0) : NULL;
        if (data) CFRelease(data);
        if (!event) {
            CFRelease(client);
            fprintf(stderr, "Unable to create simulator hinge event\n");
            return 1;
        }
        IOHIDEventSystemClientDispatchEvent(client, event);
        CFRelease(event);
        CFRelease(client);
        return 0;
    }
}
`
