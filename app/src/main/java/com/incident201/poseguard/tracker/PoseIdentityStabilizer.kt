package com.incident201.poseguard.tracker

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val MIN_POSE_LANDMARKS = 33
private const val RESET_AFTER_GAP_MS = 1200L
private const val SWITCH_MARGIN = 0.05f
private const val MIN_SCALE = 0.001f
private const val MIN_VISIBILITY_CONFIDENCE = 0.05f
private const val OUTLIER_SCORE_THRESHOLD = 0.32f
private const val HARD_OUTLIER_SCORE_THRESHOLD = 0.55f
private const val MIN_CORE_SCALE_RATIO = 0.55f
private const val MAX_CORE_SCALE_RATIO = 1.80f
private const val MIN_CORE_WIDTH_RATIO = 0.55f
private const val MAX_CORE_WIDTH_RATIO = 1.80f
private const val MIN_TORSO_LENGTH_RATIO = 0.60f
private const val MAX_TORSO_LENGTH_RATIO = 1.70f
private const val MAX_CONSECUTIVE_OUTLIERS_TO_FREEZE = 2
private const val MIN_INITIAL_CORE_SCALE = 0.02f
private const val MIN_SOFT_NORMALIZED_COORDINATE = -0.25f
private const val MAX_SOFT_NORMALIZED_COORDINATE = 1.25f

enum class PoseIdentityTransform {
    Direct,
    Swapped
}

data class PoseIdentityStabilizationResult(
    val pose: PoseLandmarks,
    val transform: PoseIdentityTransform,
    val directScore: Float,
    val swappedScore: Float,
    val ambiguous: Boolean,
    val outlier: Boolean,
    val accepted: Boolean,
    val outlierReason: String,
    val rejectReason: String = "",
    val debugDetails: String = ""
)

class PoseIdentityStabilizer {
    private var previousStablePose: PoseLandmarks? = null
    private var previousTransform: PoseIdentityTransform = PoseIdentityTransform.Direct
    private var previousTimestampMs: Long? = null
    private var consecutiveOutlierFrames = 0

    fun reset() {
        previousStablePose = null
        previousTransform = PoseIdentityTransform.Direct
        previousTimestampMs = null
        consecutiveOutlierFrames = 0
    }

    fun stabilize(rawPose: PoseLandmarks, timestampMs: Long): PoseIdentityStabilizationResult {
        if (rawPose.allLandmarks.size < MIN_POSE_LANDMARKS) {
            reset()
            return PoseIdentityStabilizationResult(
                pose = rawPose,
                transform = PoseIdentityTransform.Direct,
                directScore = 0f,
                swappedScore = 0f,
                ambiguous = false,
                outlier = false,
                accepted = false,
                outlierReason = "",
                rejectReason = "no_pose",
                debugDetails = "n=${rawPose.allLandmarks.size}"
            )
        }

        val previousTimestamp = previousTimestampMs
        if (previousTimestamp != null && timestampMs - previousTimestamp > RESET_AFTER_GAP_MS) {
            reset()
        }

        val previousPose = previousStablePose
        if (previousPose == null) {
            previousTimestampMs = timestampMs
            consecutiveOutlierFrames = 0
            if (!rawPose.hasUsableInitialIdentityCore()) {
                return PoseIdentityStabilizationResult(
                    pose = rawPose,
                    transform = PoseIdentityTransform.Direct,
                    directScore = 0f,
                    swappedScore = 0f,
                    ambiguous = false,
                    outlier = false,
                    accepted = false,
                    outlierReason = "",
                    rejectReason = "initial_core",
                    debugDetails = rawPose.initialIdentityCoreDebugDetails()
                )
            }
            previousStablePose = rawPose
            previousTransform = PoseIdentityTransform.Direct
            return PoseIdentityStabilizationResult(
                pose = rawPose,
                transform = PoseIdentityTransform.Direct,
                directScore = 0f,
                swappedScore = 0f,
                ambiguous = false,
                outlier = false,
                accepted = true,
                outlierReason = ""
            )
        }

        val directPose = rawPose
        val swappedPose = rawPose.swappedLeftRight()
        val directScore = scoreCandidate(directPose, previousPose)
        val swappedScore = scoreCandidate(swappedPose, previousPose)
        val scoresAvailable = directScore != null && swappedScore != null
        val directScoreValue = directScore ?: -1f
        val swappedScoreValue = swappedScore ?: -1f

        val chosenTransform: PoseIdentityTransform
        val ambiguous: Boolean
        if (directScore == null || swappedScore == null) {
            chosenTransform = previousTransform
            ambiguous = true
        } else if (swappedScore + SWITCH_MARGIN < directScore) {
            chosenTransform = PoseIdentityTransform.Swapped
            ambiguous = false
        } else if (directScore + SWITCH_MARGIN < swappedScore) {
            chosenTransform = PoseIdentityTransform.Direct
            ambiguous = false
        } else {
            chosenTransform = previousTransform
            ambiguous = true
        }

        val candidatePose = when (chosenTransform) {
            PoseIdentityTransform.Direct -> directPose
            PoseIdentityTransform.Swapped -> swappedPose
        }
        val chosenScore = when {
            !scoresAvailable -> Float.POSITIVE_INFINITY
            chosenTransform == PoseIdentityTransform.Direct -> directScore
            else -> swappedScore
        }
        val coreRatios = CoreGeometryRatios.from(candidatePose, previousPose)
        val geometryOutlierReason = buildGeometryOutlierReason(coreRatios)
        val outlierReason = buildOutlierReason(scoresAvailable, chosenScore, geometryOutlierReason, coreRatios)
        val candidateIsOutlier = outlierReason.isNotBlank()
        val canUpdateAmbiguousReference = scoresAvailable && chosenScore <= OUTLIER_SCORE_THRESHOLD * 0.5f

        previousTimestampMs = timestampMs

        if (!candidatePose.hasFiniteIdentityCore()) {
            return PoseIdentityStabilizationResult(
                pose = previousPose,
                transform = previousTransform,
                directScore = directScoreValue,
                swappedScore = swappedScoreValue,
                ambiguous = true,
                outlier = true,
                accepted = false,
                outlierReason = "score",
                rejectReason = "non_finite"
            )
        }

        if (!scoresAvailable && !candidateIsOutlier) {
            consecutiveOutlierFrames = 0
            return PoseIdentityStabilizationResult(
                pose = candidatePose,
                transform = chosenTransform,
                directScore = directScoreValue,
                swappedScore = swappedScoreValue,
                ambiguous = true,
                outlier = false,
                accepted = true,
                outlierReason = ""
            )
        }

        if (candidateIsOutlier) {
            consecutiveOutlierFrames += 1
            if (consecutiveOutlierFrames <= MAX_CONSECUTIVE_OUTLIERS_TO_FREEZE) {
                return PoseIdentityStabilizationResult(
                    pose = previousPose,
                    transform = previousTransform,
                    directScore = directScoreValue,
                    swappedScore = swappedScoreValue,
                    ambiguous = ambiguous,
                    outlier = true,
                    accepted = false,
                    outlierReason = outlierReason
                )
            }
        }

        consecutiveOutlierFrames = 0

        if (!ambiguous || canUpdateAmbiguousReference || candidateIsOutlier) {
            previousStablePose = candidatePose
        }
        if (!ambiguous) {
            previousTransform = chosenTransform
        }

        return PoseIdentityStabilizationResult(
            pose = candidatePose,
            transform = chosenTransform,
            directScore = directScoreValue,
            swappedScore = swappedScoreValue,
            ambiguous = ambiguous,
            outlier = false,
            accepted = true,
            outlierReason = ""
        )
    }

    private fun scoreCandidate(candidate: PoseLandmarks, previous: PoseLandmarks): Float? {
        val candidateNormalizer = PoseNormalizer.from(candidate) ?: return null
        val previousNormalizer = PoseNormalizer.from(previous) ?: return null
        var weightedDistanceSum = 0f
        var weightSum = 0f

        SCORING_LANDMARKS.forEach { scoringLandmark ->
            val currentPoint = candidate.allLandmarks.getOrNull(scoringLandmark.index) ?: return@forEach
            val previousPoint = previous.allLandmarks.getOrNull(scoringLandmark.index) ?: return@forEach
            if (!currentPoint.hasFinitePosition2D() || !previousPoint.hasFinitePosition2D()) return@forEach

            val confidence = min(
                currentPoint.visibilityConfidence(),
                previousPoint.visibilityConfidence()
            )
            if (confidence < MIN_VISIBILITY_CONFIDENCE) return@forEach

            val currentNormalized = candidateNormalizer.normalize(currentPoint)
            val previousNormalized = previousNormalizer.normalize(previousPoint)
            val dx = currentNormalized.x - previousNormalized.x
            val dy = currentNormalized.y - previousNormalized.y
            val distance = sqrt(dx * dx + dy * dy)
            val weight = scoringLandmark.weight * confidence
            weightedDistanceSum += distance * weight
            weightSum += weight
        }

        if (weightSum <= 0f) return null
        return weightedDistanceSum / weightSum
    }

    private fun buildOutlierReason(
        scoresAvailable: Boolean,
        bestScore: Float,
        geometryOutlierReason: String,
        coreRatios: CoreGeometryRatios?
    ): String {
        val reasons = mutableListOf<String>()
        if (scoresAvailable) {
            if (bestScore > OUTLIER_SCORE_THRESHOLD || bestScore > HARD_OUTLIER_SCORE_THRESHOLD) {
                reasons.add("score")
            }
        } else if (geometryOutlierReason.isNotBlank() || coreRatios == null || !coreRatios.hasAnyRatio) {
            reasons.add("score")
        }
        if (geometryOutlierReason.isNotBlank()) {
            reasons.addAll(geometryOutlierReason.split("+"))
        }
        return reasons.distinct().joinToString("+")
    }

    private fun buildGeometryOutlierReason(coreRatios: CoreGeometryRatios?): String {
        if (coreRatios == null) return ""
        val reasons = mutableListOf<String>()
        val scaleRatio = coreRatios.scaleRatio
        if (scaleRatio != null && (scaleRatio < MIN_CORE_SCALE_RATIO || scaleRatio > MAX_CORE_SCALE_RATIO)) {
            reasons.add("scale")
        }
        val widthOutlier = listOf(coreRatios.shoulderWidthRatio, coreRatios.hipWidthRatio).any { ratio ->
            ratio != null && (ratio < MIN_CORE_WIDTH_RATIO || ratio > MAX_CORE_WIDTH_RATIO)
        }
        if (widthOutlier) {
            reasons.add("width")
        }
        val torsoLengthRatio = coreRatios.torsoLengthRatio
        if (torsoLengthRatio != null &&
            (torsoLengthRatio < MIN_TORSO_LENGTH_RATIO || torsoLengthRatio > MAX_TORSO_LENGTH_RATIO)
        ) {
            reasons.add("torso")
        }
        return reasons.joinToString("+")
    }



    private fun PoseLandmarks.hasUsableInitialIdentityCore(): Boolean {
        val scoringPoints = SCORING_LANDMARKS.map { scoringLandmark ->
            allLandmarks.getOrNull(scoringLandmark.index) ?: return false
        }
        if (scoringPoints.any { !it.hasFinitePosition2D() || !it.isInsideSoftNormalizedBounds() }) {
            return false
        }

        val geometry = CoreGeometry.from(this) ?: return false
        val reliableDimensions = listOf(
            geometry.shoulderWidth,
            geometry.hipWidth,
            geometry.torsoLength
        ).count { it > MIN_SCALE }
        if (reliableDimensions < 2 || geometry.scale <= MIN_INITIAL_CORE_SCALE) {
            return false
        }

        val leftShoulder = allLandmarks.getOrNull(11)
        val rightShoulder = allLandmarks.getOrNull(12)
        val leftHip = allLandmarks.getOrNull(23)
        val rightHip = allLandmarks.getOrNull(24)
        val shoulderCenter = midpoint(leftShoulder, rightShoulder)
        val hipCenter = midpoint(leftHip, rightHip)
        return listOfNotNull(shoulderCenter, hipCenter).all { it.isInsideSoftNormalizedBounds() }
    }


    private fun PoseLandmarks.initialIdentityCoreDebugDetails(): String {
        val geometry = CoreGeometry.from(this)
        return if (geometry == null) {
            "n=${allLandmarks.size} geometry=null"
        } else {
            "n=${allLandmarks.size} sw=${geometry.shoulderWidth.formatDebugFloat()} " +
                "hw=${geometry.hipWidth.formatDebugFloat()} " +
                "torso=${geometry.torsoLength.formatDebugFloat()} " +
                "scale=${geometry.scale.formatDebugFloat()}"
        }
    }

    private fun PoseLandmarks.hasFiniteIdentityCore(): Boolean {
        return SCORING_LANDMARKS.all { scoringLandmark ->
            allLandmarks.getOrNull(scoringLandmark.index)?.hasFinitePosition2D() == true
        }
    }

    private fun PoseLandmarks.swappedLeftRight(): PoseLandmarks {
        val swapped = allLandmarks.toMutableList()
        LEFT_RIGHT_PAIRS.forEach { (leftIndex, rightIndex) ->
            val left = swapped.getOrNull(leftIndex)
            val right = swapped.getOrNull(rightIndex)
            if (left != null && right != null) {
                swapped[leftIndex] = right
                swapped[rightIndex] = left
            }
        }
        return PoseLandmarks.fromAllLandmarks(swapped)
    }

    private data class ScoringLandmark(val index: Int, val weight: Float)

    private data class NormalizedPoint(val x: Float, val y: Float)


    private data class CoreGeometryRatios(
        val scaleRatio: Float?,
        val shoulderWidthRatio: Float?,
        val hipWidthRatio: Float?,
        val torsoLengthRatio: Float?
    ) {
        val hasAnyRatio: Boolean = scaleRatio != null ||
            shoulderWidthRatio != null ||
            hipWidthRatio != null ||
            torsoLengthRatio != null

        companion object {
            fun from(candidate: PoseLandmarks, previous: PoseLandmarks): CoreGeometryRatios? {
                val candidateGeometry = CoreGeometry.from(candidate) ?: return null
                val previousGeometry = CoreGeometry.from(previous) ?: return null
                return CoreGeometryRatios(
                    scaleRatio = reliableRatio(candidateGeometry.scale, previousGeometry.scale),
                    shoulderWidthRatio = reliableRatio(candidateGeometry.shoulderWidth, previousGeometry.shoulderWidth),
                    hipWidthRatio = reliableRatio(candidateGeometry.hipWidth, previousGeometry.hipWidth),
                    torsoLengthRatio = reliableRatio(candidateGeometry.torsoLength, previousGeometry.torsoLength)
                )
            }

            private fun reliableRatio(candidateValue: Float, previousValue: Float): Float? {
                if (candidateValue < MIN_SCALE || previousValue < MIN_SCALE) return null
                return candidateValue / previousValue.coerceAtLeast(MIN_SCALE)
            }
        }
    }

    private data class CoreGeometry(
        val shoulderWidth: Float,
        val hipWidth: Float,
        val torsoLength: Float,
        val scale: Float
    ) {
        companion object {
            fun from(pose: PoseLandmarks): CoreGeometry? {
                val leftShoulder = pose.allLandmarks.getOrNull(11)?.takeIf { hasFinitePosition2D(it) }
                val rightShoulder = pose.allLandmarks.getOrNull(12)?.takeIf { hasFinitePosition2D(it) }
                val leftHip = pose.allLandmarks.getOrNull(23)?.takeIf { hasFinitePosition2D(it) }
                val rightHip = pose.allLandmarks.getOrNull(24)?.takeIf { hasFinitePosition2D(it) }
                val shoulderCenter = midpoint(leftShoulder, rightShoulder)
                val hipCenter = midpoint(leftHip, rightHip)
                if (shoulderCenter == null && hipCenter == null) return null

                val shoulderWidth = distance(leftShoulder, rightShoulder) ?: 0f
                val hipWidth = distance(leftHip, rightHip) ?: 0f
                val torsoLength = centerDistance(shoulderCenter, hipCenter) ?: 0f
                val scale = max(max(shoulderWidth, hipWidth), torsoLength).coerceAtLeast(MIN_SCALE)

                return CoreGeometry(
                    shoulderWidth = shoulderWidth,
                    hipWidth = hipWidth,
                    torsoLength = torsoLength,
                    scale = scale
                )
            }
        }
    }

    private data class PoseNormalizer(
        val centerX: Float,
        val centerY: Float,
        val scale: Float
    ) {
        fun normalize(point: Point3D): NormalizedPoint {
            return NormalizedPoint(
                x = (point.x - centerX) / scale,
                y = (point.y - centerY) / scale
            )
        }

        companion object {
            fun from(pose: PoseLandmarks): PoseNormalizer? {
                val leftShoulder = pose.allLandmarks.getOrNull(11)?.takeIf { hasFinitePosition2D(it) }
                val rightShoulder = pose.allLandmarks.getOrNull(12)?.takeIf { hasFinitePosition2D(it) }
                val leftHip = pose.allLandmarks.getOrNull(23)?.takeIf { hasFinitePosition2D(it) }
                val rightHip = pose.allLandmarks.getOrNull(24)?.takeIf { hasFinitePosition2D(it) }

                val shoulderCenter = midpoint(leftShoulder, rightShoulder)
                val hipCenter = midpoint(leftHip, rightHip)
                val bodyCenter = hipCenter ?: shoulderCenter ?: return null
                val geometry = CoreGeometry.from(pose) ?: return null

                return PoseNormalizer(bodyCenter.x, bodyCenter.y, geometry.scale)
            }
        }
    }

    private companion object {
        private val LEFT_RIGHT_PAIRS = listOf(
            1 to 4,
            2 to 5,
            3 to 6,
            7 to 8,
            9 to 10,
            11 to 12,
            13 to 14,
            15 to 16,
            17 to 18,
            19 to 20,
            21 to 22,
            23 to 24,
            25 to 26,
            27 to 28,
            29 to 30,
            31 to 32
        )

        private val SCORING_LANDMARKS = listOf(
            ScoringLandmark(11, 3.0f),
            ScoringLandmark(12, 3.0f),
            ScoringLandmark(23, 3.0f),
            ScoringLandmark(24, 3.0f),
            ScoringLandmark(13, 1.2f),
            ScoringLandmark(14, 1.2f),
            ScoringLandmark(25, 1.2f),
            ScoringLandmark(26, 1.2f)
        )

        private fun hasFinitePosition2D(point: Point3D): Boolean = point.x.isFinite() && point.y.isFinite()

        private fun midpoint(first: Point3D?, second: Point3D?): NormalizedPoint? {
            if (first == null || second == null) return null
            return NormalizedPoint(
                x = (first.x + second.x) / 2f,
                y = (first.y + second.y) / 2f
            )
        }

        private fun distance(first: Point3D?, second: Point3D?): Float? {
            if (first == null || second == null) return null
            val dx = first.x - second.x
            val dy = first.y - second.y
            return sqrt(dx * dx + dy * dy)
        }

        private fun centerDistance(first: NormalizedPoint?, second: NormalizedPoint?): Float? {
            if (first == null || second == null) return null
            val dx = first.x - second.x
            val dy = first.y - second.y
            return sqrt(dx * dx + dy * dy)
        }
    }

    private fun Point3D.hasFinitePosition2D(): Boolean = x.isFinite() && y.isFinite()

    private fun Point3D.visibilityConfidence(): Float = visibility?.coerceIn(0f, 1f) ?: 1f

    private fun Point3D.isInsideSoftNormalizedBounds(): Boolean {
        return x in MIN_SOFT_NORMALIZED_COORDINATE..MAX_SOFT_NORMALIZED_COORDINATE &&
            y in MIN_SOFT_NORMALIZED_COORDINATE..MAX_SOFT_NORMALIZED_COORDINATE
    }

    private fun NormalizedPoint.isInsideSoftNormalizedBounds(): Boolean {
        return x in MIN_SOFT_NORMALIZED_COORDINATE..MAX_SOFT_NORMALIZED_COORDINATE &&
            y in MIN_SOFT_NORMALIZED_COORDINATE..MAX_SOFT_NORMALIZED_COORDINATE
    }

    private fun Float.formatDebugFloat(): String = String.format(java.util.Locale.US, "%.3f", this)
}
