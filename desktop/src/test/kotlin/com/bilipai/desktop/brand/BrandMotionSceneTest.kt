package com.bilipai.desktop.brand

import kotlin.test.*

internal object BrandMotionTestResources {
    fun bytes(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/brand-motion/$name")) {
        "Missing fixed private test resource $name"
    }.use { it.readBytes() }
    fun scene(animation: DesktopMaidAnimation): BrandMotionScene = assertIs<BrandMotionDecodeResult.Decoded>(
        BrandMotionDecoder.decode(animation, bytes(animation.asset.jsonFileName), bytes(animation.asset.pngFileName)),
    ).scene
}

class BrandMotionSceneTest {
    @Test fun everyNonStartupOriginalResourceDecodesWithoutDroppedLayersOrMasks() {
        assertEquals(15, DesktopMaidAnimation.entries.size)
        assertEquals(14, DesktopMaidAnimation.runtimeAnimations.size)
        assertEquals(13, DesktopMaidAnimation.runtimeAnimations.map { it.asset }.distinct().size)
        for (animation in DesktopMaidAnimation.runtimeAnimations) {
            val scene = BrandMotionTestResources.scene(animation)
            assertEquals(animation.durationMs.toDouble(), scene.durationMs, 0.0001)
            assertEquals(animation.asset.pngFileName, scene.image.fileName)
            val sample = BrandMotionSampler(scene).sampleProgress(0.5)
            // All original brand top layers span the complete composition interval.
            assertEquals(scene.layers.map { it.index }, sample.layers.map { it.source.index })
            assertEquals(scene.layers.sumOf { it.masks.size }, sample.layers.sumOf { it.source.masks.size })
            assertTrue(sample.layers.any { it.children.any { child -> child.source.type == BrandMotionLayerType.IMAGE } })
            assertTrue(scene.layers.any { it.masks.any { mask -> mask.mode == BrandMotionMaskMode.SUBTRACT } })
            assertTrue(scene.layers.any { it.masks.any { mask -> mask.mode == BrandMotionMaskMode.ADD } })
        }
    }

    @Test fun originalLikeAliasAndCleaningLoopAreRetainedButWelcomeIsDenied() {
        assertEquals(DesktopMaidAnimation.DOWNLOAD_COMPLETE.asset, DesktopMaidAnimation.LIKE_SUCCESS.asset)
        assertTrue(DesktopMaidAnimation.CLEANING.loopsWhileVisible)
        assertEquals(listOf(DesktopMaidAnimation.CLEANING), DesktopMaidAnimation.entries.filter { it.loopsWhileVisible })
        val rejected = assertIs<BrandMotionDecodeResult.Rejected>(
            BrandMotionDecoder.decode(DesktopMaidAnimation.WELCOME, byteArrayOf(), byteArrayOf()),
        )
        assertFalse(rejected.fallbackVerified)
        assertNull(rejected.fallbackFileName)
    }

    @Test fun hashFailureDoesNotClaimAnAnimationOrUnverifiedFallbackReady() {
        val animation = DesktopMaidAnimation.EMPTY
        val json = BrandMotionTestResources.bytes(animation.asset.jsonFileName)
        val png = BrandMotionTestResources.bytes(animation.asset.pngFileName)
        val changedJson = json.copyOf().also { it[0] = 32 }
        val result = assertIs<BrandMotionDecodeResult.Rejected>(BrandMotionDecoder.decode(animation, changedJson, png))
        assertTrue(result.fallbackVerified)
        assertEquals(animation.asset.pngFileName, result.fallbackFileName)
        val changedPng = png.copyOf().also { it[it.lastIndex] = 0 }
        assertFalse(assertIs<BrandMotionDecodeResult.Rejected>(BrandMotionDecoder.decode(animation, json, changedPng)).fallbackVerified)
    }

    @Test fun activeRangeIsIpInclusiveOpExclusiveAndCompletedPoseRemainsSampleable() {
        val scene = BrandMotionTestResources.scene(DesktopMaidAnimation.TRIPLE_SUCCESS)
        val sampler = BrandMotionSampler(scene)
        assertTrue(sampler.sampleFrame(scene.inFrame - 0.01).layers.isEmpty())
        assertTrue(sampler.sampleFrame(scene.inFrame).layers.isNotEmpty())
        assertTrue(sampler.sampleFrame(scene.outFrame).layers.isEmpty())
        assertTrue(sampler.sampleProgress(1.0).layers.isNotEmpty())
        assertEquals(Math.nextDown(scene.outFrame), sampler.sampleProgress(1.0).frame)
        assertEquals(sampler.sampleProgress(0.5), sampler.sampleElapsedMs(scene.durationMs / 2.0))
    }

    @Test fun originalStaticPathsAndShapeStyleOrderReachTheSampleUnchanged() {
        val scene = BrandMotionTestResources.scene(DesktopMaidAnimation.CLEAN_COMPLETE)
        val sampled = BrandMotionSampler(scene).sampleProgress(0.25)
        val shapeLayer = sampled.layers.first { it.source.shapes.isNotEmpty() }
        val source = assertIs<BrandMotionShape.Group>(shapeLayer.source.shapes.first())
        val sample = assertIs<BrandMotionSampledShape.Group>(shapeLayer.shapes.first())
        assertEquals(source.name, sample.name)
        assertEquals(source.items, sample.items.map { assertIs<BrandMotionSampledShape.Static>(it).source })
        val path = shapeLayer.source.masks.firstOrNull()?.path ?: sampled.layers.first { it.source.masks.isNotEmpty() }.source.masks.first().path
        assertEquals(path.vertices.size, path.incoming.size)
        assertEquals(path.vertices.size, path.outgoing.size)
    }

    @Test fun easingSolvesTemporalXAndUsesIndependentComponentCurves() {
        val linear = BrandMotionBezier(1.0 / 3.0, 1.0 / 3.0, 2.0 / 3.0, 2.0 / 3.0)
        val ease = BrandMotionBezier(1.0 / 3.0, 1.0, 2.0 / 3.0, 1.0)
        val value = BrandMotionProperty(null, listOf(
            BrandMotionKeyframe(0.0, listOf(0.0, 100.0), listOf(10.0, 0.0), listOf(linear, ease)),
            BrandMotionKeyframe(10.0, listOf(10.0, 0.0), null, null),
        ))
        assertEquals(5.0, value.sample(5.0)[0], 1e-8)
        assertEquals(12.5, value.sample(5.0)[1], 1e-8)
        assertEquals(listOf(0.0, 100.0), value.sample(-1.0))
        assertEquals(listOf(10.0, 0.0), value.sample(10.0))
    }

    @Test fun fixedCleaningKeyframeAndItsRealParentRotationAreConsumed() {
        val scene = BrandMotionTestResources.scene(DesktopMaidAnimation.CLEANING)
        val broom = scene.layers.first()
        assertEquals(-2.0, broom.transform.rotation.sample(24.0).single())
        val parent = scene.layers.single { it.index == broom.parent }
        assertEquals(-0.4, parent.transform.rotation.sample(24.0).single())
        val sampled = BrandMotionSampler(scene).sampleFrame(24.0).layers.first()
        assertEquals(kotlin.math.cos(Math.toRadians(-2.4)), sampled.matrix.a, 1e-8)
        assertEquals(kotlin.math.sin(Math.toRadians(-2.4)), sampled.matrix.b, 1e-8)
    }

    @Test fun parentTransformsApplyWithoutParentOpacityAndPrecompOwnAlphaIsSeparate() {
        fun constant(vararg value: Double) = BrandMotionProperty(value.toList(), emptyList())
        fun transform(x: Double, y: Double, rotation: Double, alpha: Double) = BrandMotionTransform(
            constant(x, y, 0.0), constant(0.0, 0.0, 0.0), constant(100.0, 100.0, 100.0), constant(rotation), constant(alpha),
        )
        fun layer(id: Int, type: BrandMotionLayerType, parent: Int?, x: Double, y: Double, r: Double, alpha: Double, ref: String? = null) =
            BrandMotionLayer(id, "layer$id", type, parent, transform(x, y, r, alpha), 0.0, 60.0, 0.0, 1.0,
                ref, if (ref != null) 512 else null, if (ref != null) 512 else null, emptyList(), emptyList())
        val parent = layer(1, BrandMotionLayerType.NULL, null, 10.0, 20.0, 90.0, 25.0)
        val precomp = layer(2, BrandMotionLayerType.PRECOMPOSITION, 1, 1.0, 2.0, 0.0, 50.0, "art")
        val image = layer(3, BrandMotionLayerType.IMAGE, null, 0.0, 0.0, 0.0, 50.0, "image")
        val scene = BrandMotionScene("test", 512, 512, 60.0, 0.0, 60.0, listOf(parent, precomp),
            mapOf("art" to listOf(image)), BrandMotionImage("image", "test.png", 512, 512))
        val sampled = BrandMotionSampler(scene).sampleFrame(1.0).layers[1]
        val point = sampled.matrix.map(BrandMotionPoint(0.0, 0.0))
        assertEquals(8.0, point.x, 1e-8)
        assertEquals(21.0, point.y, 1e-8)
        assertEquals(0.5, sampled.ownOpacity)
        assertEquals(0.5, sampled.worldOpacity) // NULL parent's 0.25 does not propagate.
        assertEquals(0.5, sampled.children.single().ownOpacity)
        assertEquals(0.25, sampled.children.single().worldOpacity) // diagnostic, not renderer alpha.
    }
}
