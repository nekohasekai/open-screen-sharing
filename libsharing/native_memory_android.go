package libsharing

/*
#include <jni.h>
#include <stdint.h>

JNIEXPORT jlong JNICALL
Java_io_nekohasekai_screensharing_session_NativeMemory_address(JNIEnv *env, jclass clazz, jobject buffer) {
	return (jlong)(intptr_t)(*env)->GetDirectBufferAddress(env, buffer);
}
*/
import "C"
