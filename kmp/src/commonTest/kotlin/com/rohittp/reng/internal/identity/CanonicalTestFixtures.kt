package com.rohittp.reng.internal.identity

import com.rohittp.reng.AnchoringMode
import com.rohittp.reng.AnimationSelector
import com.rohittp.reng.AnimationTrack
import com.rohittp.reng.Camera
import com.rohittp.reng.FramePlan
import com.rohittp.reng.Geometry
import com.rohittp.reng.Model
import com.rohittp.reng.Placement
import com.rohittp.reng.ProjectionMode
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Sticker
import com.rohittp.reng.Vector3

internal fun canonicalV1MinimalFramePlan(): FramePlan = FramePlan(
    frameIndex = 0,
    camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
)

internal fun canonicalV1RepresentativeFramePlan(): FramePlan = FramePlan(
    frameIndex = 42,
    camera = Camera(12.5, -179.25, 8.5, 45.0, 25.0),
    projectionMode = ProjectionMode.MERCATOR,
    drawBasemap = true,
    stickers = listOf(
        Sticker(
            Placement(
                AnchoringMode.MAP,
                Vector3(12.0, -179.0, 15.0),
                AnchoringMode.SCREEN,
                Vector3(0.0, 15.0, -0.0),
                AnchoringMode.SCREEN,
                2.0,
            ),
            ResourceLocator("stickers/café.png"),
        ),
        Sticker(
            Placement(
                AnchoringMode.SCREEN,
                Vector3(640.0, 360.0, 4.0),
                AnchoringMode.SCREEN,
                Vector3(0.0, 0.0, 0.0),
                AnchoringMode.SCREEN,
                1.0,
            ),
            ResourceLocator("https://example.test/sticker%20two.png"),
        ),
    ),
    models = listOf(
        Model(
            Placement(
                AnchoringMode.MAP,
                Vector3(11.0, -178.0, 20.0),
                AnchoringMode.MAP,
                Vector3(10.0, 20.0, 30.0),
                AnchoringMode.MAP,
                3.0,
            ),
            ResourceLocator("models/robot.glb"),
            ResourceLocator("textures/robot-base.png"),
            listOf(
                AnimationTrack(AnimationSelector.Name("idle"), 1.5),
                AnimationTrack(AnimationSelector.Index(7), 2.0),
            ),
        ),
        Model(
            Placement(
                AnchoringMode.SCREEN,
                Vector3(200.0, 150.0, 5.0),
                AnchoringMode.SCREEN,
                Vector3(0.0, 0.0, 0.0),
                AnchoringMode.SCREEN,
                0.5,
            ),
            ResourceLocator("models/screen.glb"),
            null,
            emptyList(),
        ),
    ),
    geometries = listOf(
        Geometry(
            Vector3(20.0, -180.0, 10.0),
            Vector3(10.0, -170.0, 20.0),
            ShaderPair(
                "#version 300 es\nin vec3 position;\nvoid main(){gl_Position=vec4(position,1.0);}",
                "#version 300 es\nprecision highp float;\nout vec4 color;\nvoid main(){color=vec4(1.0);}",
            ),
        ),
        Geometry(
            Vector3(40.0, -160.0, 0.0),
            Vector3(30.0, -150.0, 0.0),
            ShaderPair("#version 300 es\nvoid main(){}", "#version 300 es\nvoid main(){}"),
        ),
    ),
)

internal const val CANONICAL_V1_REPRESENTATIVE_HEX: String =
    "524e47430101000100000008000000000000002a0002000000460001000000084029000000000000000200000008c06668000000000000030000000840210000000000000004000000084046800000000000000500000008403900000000000000030000000200010004000000010100050000016800000002000000a4000100000086000100000002000100020000002a0001000000084028000000000000000200000008c066600000000000000300000008402e000000000000000300000002000200040000002a0001000000080000000000000000000200000008402e000000000000000300000008000000000000000000050000000200020006000000084000000000000000000200000012737469636b6572732f636166c3a92e706e67000000b8000100000086000100000002000200020000002a000100000008408400000000000000020000000840768000000000000003000000084010000000000000000300000002000200040000002a00010000000800000000000000000002000000080000000000000000000300000008000000000000000000050000000200020006000000083ff000000000000000020000002668747470733a2f2f6578616d706c652e746573742f737469636b657225323074776f2e706e670006000001e20000000200000122000100000086000100000002000100020000002a0001000000084026000000000000000200000008c0664000000000000003000000084034000000000000000300000002000100040000002a00010000000840240000000000000002000000084034000000000000000300000008403e000000000000000500000002000100060000000840080000000000000002000000106d6f64656c732f726f626f742e676c620003000000180174657874757265732f726f626f742d626173652e706e6700040000005c0000000200000026000100000012000100000002000200020000000469646c650002000000083ff80000000000000000002a000100000016000100000002000100020000000800000000000000070002000000084000000000000000000000b4000100000086000100000002000200020000002a00010000000840690000000000000002000000084062c000000000000003000000084014000000000000000300000002000200040000002a00010000000800000000000000000002000000080000000000000000000300000008000000000000000000050000000200020006000000083fe00000000000000002000000116d6f64656c732f73637265656e2e676c6200030000000100000400000004000000000007000001f4000000020000012800010000002a0001000000084034000000000000000200000008c066800000000000000300000008402400000000000000020000002a0001000000084024000000000000000200000008c06540000000000000030000000840340000000000000003000000ae00010000004e2376657273696f6e203330302065730a696e207665633320706f736974696f6e3b0a766f6964206d61696e28297b676c5f506f736974696f6e3d7665633428706f736974696f6e2c312e30293b7d0002000000542376657273696f6e203330302065730a707265636973696f6e20686967687020666c6f61743b0a6f7574207665633420636f6c6f723b0a766f6964206d61696e28297b636f6c6f723d7665633428312e30293b7d0004000000040000000000050000000400000000000000c000010000002a0001000000084044000000000000000200000008c064000000000000000300000008000000000000000000020000002a000100000008403e000000000000000200000008c062c00000000000000300000008000000000000000000030000004600010000001d2376657273696f6e203330302065730a766f6964206d61696e28297b7d00020000001d2376657273696f6e203330302065730a766f6964206d61696e28297b7d000400000004000000000005000000040000000000080000000101"

internal fun String.canonicalFixtureHexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun CanonicalBytes.fixtureLowercaseHex(): String = bytes.toFixtureLowercaseHex()

internal fun ByteArray.toFixtureLowercaseHex(): String = buildString(size * 2) {
    this@toFixtureLowercaseHex.forEach { byte ->
        append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
        append(HEX_DIGITS[byte.toInt() and 0x0f])
    }
}

private const val HEX_DIGITS: String = "0123456789abcdef"
