package io.nekohasekai.screensharing.session

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.GLES30
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import io.nekohasekai.screensharing.app.MessageError

enum class VideoTransfer {
    Srgb,
    Pq,
    Hlg,
}

enum class VideoPrimaries(private val toXYZ: DoubleArray) {
    Srgb(doubleArrayOf(0.4124564, 0.3575761, 0.1804375, 0.2126729, 0.7151522, 0.0721750, 0.0193339, 0.1191920, 0.9503041)),
    DisplayP3(doubleArrayOf(0.4865709, 0.2656677, 0.1982173, 0.2289746, 0.6917385, 0.0792869, 0.0, 0.0451134, 1.0439444)),
    Bt2020(doubleArrayOf(0.6369580, 0.1446169, 0.1688810, 0.2627002, 0.6779981, 0.0593017, 0.0, 0.0280727, 1.0609851)),
    ;

    fun conversion(to: VideoPrimaries): FloatArray = conversions[ordinal][to.ordinal]

    private companion object {
        val conversions: Array<Array<FloatArray>> = Array(entries.size) { from ->
            Array(entries.size) { to ->
                val matrix = multiply(invert(entries[to].toXYZ), entries[from].toXYZ)
                FloatArray(9) { index -> matrix[(index % 3) * 3 + index / 3].toFloat() }
            }
        }

        fun multiply(left: DoubleArray, right: DoubleArray) = DoubleArray(9) { index ->
            val row = index / 3
            val column = index % 3
            left[row * 3] * right[column] + left[row * 3 + 1] * right[3 + column] + left[row * 3 + 2] * right[6 + column]
        }

        fun invert(m: DoubleArray): DoubleArray {
            val c0 = m[4] * m[8] - m[5] * m[7]
            val c1 = m[5] * m[6] - m[3] * m[8]
            val c2 = m[3] * m[7] - m[4] * m[6]
            val determinant = m[0] * c0 + m[1] * c1 + m[2] * c2
            return doubleArrayOf(
                c0 / determinant, (m[2] * m[7] - m[1] * m[8]) / determinant, (m[1] * m[5] - m[2] * m[4]) / determinant,
                c1 / determinant, (m[0] * m[8] - m[2] * m[6]) / determinant, (m[2] * m[3] - m[0] * m[5]) / determinant,
                c2 / determinant, (m[1] * m[6] - m[0] * m[7]) / determinant, (m[0] * m[4] - m[1] * m[3]) / determinant,
            )
        }
    }
}

internal object Gl {
    fun chooseConfig(display: EGLDisplay, red: Int, green: Int, blue: Int, alpha: Int, surfaceType: Int): EGLConfig? {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, red,
            EGL14.EGL_GREEN_SIZE, green,
            EGL14.EGL_BLUE_SIZE, blue,
            EGL14.EGL_ALPHA_SIZE, alpha,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, surfaceType,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(64)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, configs.size, count, 0)) return null
        val value = IntArray(1)
        fun attribute(config: EGLConfig, name: Int) = if (EGL14.eglGetConfigAttrib(display, config, name, value, 0)) value[0] else -1
        return configs.take(count[0]).filterNotNull().firstOrNull { config ->
            attribute(config, EGL14.EGL_RED_SIZE) == red && attribute(config, EGL14.EGL_GREEN_SIZE) == green &&
                attribute(config, EGL14.EGL_BLUE_SIZE) == blue && attribute(config, EGL14.EGL_ALPHA_SIZE) == alpha
        }
    }

    fun eglFailure(operation: String) = MessageError("$operation failed with EGL error 0x${Integer.toHexString(EGL14.eglGetError())}")

    fun program(vertex: String, fragment: String): Int {
        val vertexShader = shader(GLES30.GL_VERTEX_SHADER, vertex)
        val fragmentShader = try {
            shader(GLES30.GL_FRAGMENT_SHADER, fragment)
        } catch (e: MessageError) {
            GLES30.glDeleteShader(vertexShader)
            throw e
        }
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw MessageError("link a shader program: $log")
        }
        return program
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw MessageError("compile a shader: $log")
        }
        return shader
    }

    fun texture(width: Int, height: Int, format: Int, levels: Int = 1): Int {
        val names = IntArray(1)
        GLES30.glGenTextures(1, names, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, names[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, format, width, height)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return names[0]
    }

    fun deleteTexture(texture: Int) {
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    fun ndc(rect: Rect, size: Size, flipped: Boolean = false): FloatArray {
        val top = rect.top / size.height * 2 - 1
        val bottom = rect.bottom / size.height * 2 - 1
        return floatArrayOf(
            rect.left / size.width * 2 - 1,
            if (flipped) top else -top,
            rect.right / size.width * 2 - 1,
            if (flipped) bottom else -bottom,
        )
    }
}

internal open class QuadProgram(fragment: String) {
    val program = Gl.program(Shaders.QUAD_VERTEX, fragment)
    private val rect = location("rect")
    private val textureRect = location("textureRect")
    private val turns = location("turns")

    init {
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(location("image"), 0)
        GLES30.glUniform1i(location("inversion"), 1)
    }

    fun location(name: String) = GLES30.glGetUniformLocation(program, name)

    fun use() {
        GLES30.glUseProgram(program)
    }

    fun draw(rect: FloatArray, textureRect: FloatArray = unitRect, turns: Int = 0) {
        GLES30.glUseProgram(program)
        GLES30.glUniform4fv(this.rect, 1, rect, 0)
        GLES30.glUniform4fv(this.textureRect, 1, textureRect, 0)
        GLES30.glUniform1i(this.turns, turns)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun delete() {
        GLES30.glDeleteProgram(program)
    }

    companion object {
        val unitRect = floatArrayOf(0f, 0f, 1f, 1f)
    }
}

internal class PictureProgram : QuadProgram(Shaders.PICTURE_FRAGMENT) {
    private val converts = location("converts")
    private val inputTransfer = location("inputTransfer")
    private val outputTransfer = location("outputTransfer")
    private val primaries = location("primaries")

    fun setConversion(input: VideoTransfer, inputPrimaries: VideoPrimaries, output: VideoTransfer, outputPrimaries: VideoPrimaries) {
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(converts, if (input != output || inputPrimaries != outputPrimaries) 1 else 0)
        GLES30.glUniform1i(inputTransfer, input.ordinal)
        GLES30.glUniform1i(outputTransfer, output.ordinal)
        GLES30.glUniformMatrix3fv(primaries, 1, false, inputPrimaries.conversion(outputPrimaries), 0)
    }
}

internal object Shaders {
    const val QUAD_VERTEX = """#version 300 es
uniform vec4 rect;
uniform vec4 textureRect;
uniform int turns;
out vec2 textureCoordinate;
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vec2 source = corner;
    for (int turn = 0; turn < turns; turn++) {
        source = vec2(source.y, 1.0 - source.x);
    }
    gl_Position = vec4(mix(rect.xy, rect.zw, corner), 0.0, 1.0);
    textureCoordinate = mix(textureRect.xy, textureRect.zw, source);
}
"""

    private const val COLOR = """
const float sdrWhite = 203.0 / 10000.0;
vec3 srgbDecode(vec3 encoded) {
    return mix(encoded / 12.92, pow((max(encoded, 0.0) + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), encoded));
}
vec3 srgbEncode(vec3 linear) {
    return mix(linear * 12.92, 1.055 * pow(max(linear, 0.0), vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), linear));
}
vec3 pqDecode(vec3 encoded) {
    vec3 p = pow(max(encoded, 0.0), vec3(1.0 / 78.84375));
    return pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), vec3(1.0 / 0.1593017578125));
}
vec3 pqEncode(vec3 linear) {
    vec3 scaled = pow(max(linear, 0.0), vec3(0.1593017578125));
    return pow((0.8359375 + 18.8515625 * scaled) / (1.0 + 18.6875 * scaled), vec3(78.84375));
}
vec3 hlgDecode(vec3 encoded) {
    vec3 low = encoded * encoded / 3.0;
    vec3 high = (exp((encoded - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
    return mix(low, high, step(vec3(0.5), encoded));
}
"""

    const val PICTURE_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D image;
uniform bool converts;
uniform int inputTransfer;
uniform int outputTransfer;
uniform mat3 primaries;
in vec2 textureCoordinate;
out vec4 fragColor;
$COLOR
void main() {
    vec3 color = texture(image, textureCoordinate).rgb;
    if (converts) {
        vec3 linear;
        if (inputTransfer == 1) {
            linear = pqDecode(color) / sdrWhite;
        } else if (inputTransfer == 2) {
            linear = pow(hlgDecode(color), vec3(1.2)) * (1000.0 / 203.0);
        } else {
            linear = srgbDecode(color);
        }
        linear = max(primaries * linear, 0.0);
        color = outputTransfer == 1 ? pqEncode(linear * sdrWhite) : srgbEncode(min(linear, 1.0));
    }
    fragColor = vec4(color, 1.0);
}
"""

    const val BLACK_FRAGMENT = """#version 300 es
precision mediump float;
out vec4 fragColor;
void main() {
    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
}
"""

    const val CURSOR_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D image;
uniform int outputTransfer;
uniform mat3 primaries;
in vec2 textureCoordinate;
out vec4 fragColor;
$COLOR
void main() {
    vec4 color = texture(image, textureCoordinate);
    if (outputTransfer == 1) {
        if (color.a <= 0.0) {
            fragColor = vec4(0.0);
            return;
        }
        vec3 linear = max(primaries * srgbDecode(color.rgb / color.a), 0.0);
        fragColor = vec4(pqEncode(linear * sdrWhite) * color.a, color.a);
    } else {
        fragColor = color;
    }
}
"""

    const val MASKED_CURSOR_FRAGMENT = """#version 300 es
precision mediump float;
uniform sampler2D image;
uniform sampler2D inversion;
in vec2 textureCoordinate;
out vec4 fragColor;
void main() {
    fragColor = texture(image, textureCoordinate) * (1.0 - texture(inversion, textureCoordinate).r);
}
"""

    const val CURSOR_INVERSION_FRAGMENT = """#version 300 es
precision mediump float;
uniform sampler2D inversion;
in vec2 textureCoordinate;
out vec4 fragColor;
void main() {
    fragColor = vec4(texture(inversion, textureCoordinate).r);
}
"""

    const val EXTERNAL_COPY_VERTEX = """
attribute vec2 position;
uniform mat4 transform;
varying vec2 coordinate;
void main() {
    gl_Position = vec4(position, 0.0, 1.0);
    vec2 unit = position * 0.5 + 0.5;
    coordinate = (transform * vec4(unit.x, 1.0 - unit.y, 0.0, 1.0)).xy;
}
"""

    const val EXTERNAL_COPY_FRAGMENT = """#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES image;
varying vec2 coordinate;
void main() {
    gl_FragColor = vec4(texture2D(image, coordinate).rgb, 1.0);
}
"""
}
