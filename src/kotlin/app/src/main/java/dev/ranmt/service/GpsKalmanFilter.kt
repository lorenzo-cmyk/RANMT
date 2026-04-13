package dev.ranmt.service

import android.location.Location
import android.os.Build
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A Kalman filter for GPS-based speed estimation.
 *
 * State vector: [x, y, vx, vy]
 *   - x, y  : position in meters, local ENU frame (East-North)
 *   - vx, vy: velocity in m/s, local ENU frame
 *
 * Motion model: constant velocity + stochastic acceleration (Singer model).
 * Measurement model: GPS position only (x, y). GPS Doppler speed is fused
 *   separately when available and reliable (API 26+).
 *
 * @param accelerationNoiseMps2  Expected std dev of acceleration in m/s².
 *                               Trains: 0.3–0.5. Cars: 1.5–3.0.
 * @param maxPlausibleAccelMps2  Hard cap for outlier rejection. Fixes implying
 *                               higher acceleration are partially down-weighted.
 *                               Trains: 1.0–1.5. Cars: 4.0–6.0.
 * @param tunnelTimeoutSeconds   Seconds without a valid fix before switching to
 *                               dead-reckoning (prediction only) mode.
 * @param minFixAccuracyMeters   Fixes with horizontal accuracy worse than this
 *                               are still used but weighted by their reported
 *                               accuracy, so this is a soft threshold.
 */
class GpsKalmanFilter(
    private val accelerationNoiseMps2: Double = 1.5,
    private val maxPlausibleAccelMps2: Double = 4.0,
    private val tunnelTimeoutSeconds: Double = 8.0,
    private val minFixAccuracyMeters: Float = 80f
) {

    // ── State ──────────────────────────────────────────────────────────────────

    private var x = 0.0   // east  (m)
    private var y = 0.0   // north (m)
    private var vx = 0.0   // east  velocity (m/s)
    private var vy = 0.0   // north velocity (m/s)

    // 4×4 error covariance matrix, row-major
    private val P = Array(4) { i -> DoubleArray(4) { j -> if (i == j) 1.0 else 0.0 } }

    // Local ENU frame origin
    private var originLat = 0.0
    private var originLng = 0.0

    private var lastTimestampNs = 0L
    private var lastValidFixNs = 0L
    private var initialized = false

    // ── Public output ──────────────────────────────────────────────────────────

    data class FilteredState(
        /** Filtered latitude in degrees. */
        val latitude: Double,
        /** Filtered longitude in degrees. */
        val longitude: Double,
        /** Filtered speed in m/s. Always a valid, non-negative number. */
        val speedMs: Float,
        /** Filtered bearing in degrees [0, 360). 0 = north, 90 = east. */
        val bearingDeg: Float,
        /**
         * True when no valid GPS fix has arrived for [tunnelTimeoutSeconds].
         * Speed is extrapolated from the last known velocity — useful to show
         * a "signal lost" indicator in the UI without dropping the value to 0.
         */
        val isCoasting: Boolean
    )

    // ── Main entry point ───────────────────────────────────────────────────────

    fun update(location: Location): FilteredState {
        val nowNs = location.elapsedRealtimeNanos

        if (!initialized) return initialize(location)

        val dt = (nowNs - lastTimestampNs).toDouble() / 1e9
        lastTimestampNs = nowNs

        if (dt <= 0.0) return currentState(isCoasting())

        // 1. Predict forward in time
        predict(dt)

        // 2. Decide whether this fix is usable
        val fixUsable = location.accuracy <= minFixAccuracyMeters

        if (fixUsable) {
            lastValidFixNs = nowNs

            val (measX, measY) = toLocalEnu(location.latitude, location.longitude)

            // 3. Outlier rejection — scale down corrections that imply impossible acceleration
            val innovX = measX - x
            val innovY = measY - y
            val correctionScale = computeCorrectionScale(innovX, innovY, dt)

            // 4. Measurement noise — derived from the GPS chip's reported accuracy
            //    R = σ² where σ = horizontal accuracy (1-sigma, 68% confidence)
            val rPos = (location.accuracy * location.accuracy).toDouble()

            // 5. Position update (always available)
            updatePosition(innovX * correctionScale, innovY * correctionScale, rPos)

            // 6. Doppler speed fusion (API 26+, only when chip reports it reliable)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && location.hasSpeed()
                && location.hasSpeedAccuracy()
                && location.speedAccuracyMetersPerSecond < 0.8f
            ) {
                fuseDopplerSpeed(
                    dopplerSpeed = location.speed.toDouble(),
                    dopplerAccuracy = location.speedAccuracyMetersPerSecond.toDouble()
                )
            }
        }

        return currentState(isCoasting())
    }

    /** Call this when the session ends or the user restarts capture. */
    fun reset() {
        initialized = false
        lastTimestampNs = 0L
        lastValidFixNs = 0L
    }

    // ── Kalman predict step ────────────────────────────────────────────────────

    /**
     * Constant-velocity prediction with discrete white noise acceleration model.
     *
     * State transition:  F = | 1  0  dt  0 |
     *                        | 0  1   0 dt |
     *                        | 0  0   1  0 |
     *                        | 0  0   0  1 |
     *
     * Process noise Q (DWNA):
     *   Q = qa² × | dt⁴/4   0     dt³/2   0   |
     *              |  0    dt⁴/4    0    dt³/2  |
     *              | dt³/2   0     dt²     0   |
     *              |  0    dt³/2    0     dt²   |
     */
    private fun predict(dt: Double) {
        // State extrapolation
        x += vx * dt
        y += vy * dt
        // vx, vy unchanged under constant-velocity model

        // Covariance extrapolation: P = F·P·Fᵀ + Q
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt2 * dt2
        val qa2 = accelerationNoiseMps2 * accelerationNoiseMps2

        // F·P·Fᵀ — apply F from the left, Fᵀ from the right
        // Left multiply by F (add dt × row 2 to row 0, dt × row 3 to row 1)
        val tmp = Array(4) { i -> P[i].copyOf() }
        for (j in 0..3) {
            tmp[0][j] = P[0][j] + dt * P[2][j]
            tmp[1][j] = P[1][j] + dt * P[3][j]
        }
        // Right multiply by Fᵀ (add dt × col 2 to col 0, dt × col 3 to col 1)
        for (i in 0..3) {
            P[i][0] = tmp[i][0] + dt * tmp[i][2]
            P[i][1] = tmp[i][1] + dt * tmp[i][3]
            P[i][2] = tmp[i][2]
            P[i][3] = tmp[i][3]
        }

        // Add process noise Q
        P[0][0] += qa2 * dt4 / 4.0
        P[1][1] += qa2 * dt4 / 4.0
        P[2][2] += qa2 * dt2
        P[3][3] += qa2 * dt2
        P[0][2] += qa2 * dt3 / 2.0; P[2][0] += qa2 * dt3 / 2.0
        P[1][3] += qa2 * dt3 / 2.0; P[3][1] += qa2 * dt3 / 2.0
    }

    // ── Kalman update — position measurement ──────────────────────────────────

    /**
     * Standard Kalman update for H = | 1 0 0 0 |
     *                                 | 0 1 0 0 |
     * S = H·P·Hᵀ + R  (2×2)
     * K = P·Hᵀ·S⁻¹    (4×2)
     * x = x + K·innov
     * P = (I − K·H)·P
     */
    private fun updatePosition(innovX: Double, innovY: Double, rPos: Double) {
        // S = top-left 2×2 of P + R·I
        val s00 = P[0][0] + rPos
        val s01 = P[0][1]
        val s10 = P[1][0]
        val s11 = P[1][1] + rPos
        val det = s00 * s11 - s01 * s10
        if (abs(det) < 1e-10) return  // degenerate — skip update

        // K = P · Hᵀ · S⁻¹   (Hᵀ extracts columns 0 and 1 from P)
        val k = Array(4) { i ->
            val p0 = P[i][0]
            val p1 = P[i][1]
            doubleArrayOf(
                (p0 * s11 - p1 * s10) / det,
                (-p0 * s01 + p1 * s00) / det
            )
        }

        // State update
        x += k[0][0] * innovX + k[0][1] * innovY
        y += k[1][0] * innovX + k[1][1] * innovY
        vx += k[2][0] * innovX + k[2][1] * innovY
        vy += k[3][0] * innovX + k[3][1] * innovY

        // Covariance update: P = (I − K·H)·P
        // K·H subtracts k[:,0]·P[0,:] and k[:,1]·P[1,:] from each row
        val oldP = Array(4) { P[it].copyOf() }
        for (i in 0..3) {
            for (j in 0..3) {
                P[i][j] = oldP[i][j] - k[i][0] * oldP[0][j] - k[i][1] * oldP[1][j]
            }
        }
    }

    // ── Doppler speed fusion ───────────────────────────────────────────────────

    /**
     * Fuses the GPS chip's Doppler-derived speed (a scalar) into the velocity
     * components. The measurement model projects the state velocity onto the
     * current heading direction.
     *
     * This is a scalar update: z = speed, h(x) = ‖[vx, vy]‖
     * Linearised: H_speed = [vx, vy] / speed  (unit heading vector)
     */
    private fun fuseDopplerSpeed(dopplerSpeed: Double, dopplerAccuracy: Double) {
        val currentSpeed = sqrt(vx * vx + vy * vy)
        if (currentSpeed < 0.5) return  // avoid division by near-zero

        val hx = vx / currentSpeed
        val hy = vy / currentSpeed

        // S = H·P·Hᵀ + R  (scalar)
        val s = (hx * hx * P[2][2]
                + 2 * hx * hy * P[2][3]
                + hy * hy * P[3][3]
                + dopplerAccuracy * dopplerAccuracy)

        if (s < 1e-10) return

        // K = P·Hᵀ / S  (4-element vector, only velocity rows matter here)
        val k2 = (P[2][2] * hx + P[2][3] * hy) / s
        val k3 = (P[3][2] * hx + P[3][3] * hy) / s

        val innov = dopplerSpeed - currentSpeed
        vx += k2 * innov
        vy += k3 * innov

        // Covariance update for velocity rows only
        // Cache original values before in-place modification
        val p22 = P[2][2];
        val p23 = P[2][3]
        val p32 = P[3][2];
        val p33 = P[3][3]

        val hp2 = p22 * hx + p32 * hy
        val hp3 = p23 * hx + p33 * hy

        P[2][2] -= k2 * hp2
        P[2][3] -= k2 * hp3
        P[3][2] -= k3 * hp2
        P[3][3] -= k3 * hp3
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Scales the position correction to prevent velocity updates that would
     * imply physically impossible acceleration.
     * Returns 1.0 when the fix is plausible, <1.0 when it is not.
     */
    private fun computeCorrectionScale(innovX: Double, innovY: Double, dt: Double): Double {
        if (dt <= 0.0) return 0.0
        // Velocity change implied by fully accepting this fix
        val deltaVx = innovX / dt
        val deltaVy = innovY / dt
        val impliedAccel = sqrt(deltaVx * deltaVx + deltaVy * deltaVy) / dt
        return if (impliedAccel > maxPlausibleAccelMps2)
            maxPlausibleAccelMps2 / impliedAccel
        else
            1.0
    }

    private fun initialize(location: Location): FilteredState {
        originLat = location.latitude
        originLng = location.longitude
        x = 0.0; y = 0.0; vx = 0.0; vy = 0.0

        val posVar = (location.accuracy * location.accuracy).toDouble()
        for (i in 0..3) P[i].fill(0.0)
        P[0][0] = posVar
        P[1][1] = posVar
        P[2][2] = 900.0   // ±30 m/s velocity uncertainty at start (wide prior)
        P[3][3] = 900.0

        lastTimestampNs = location.elapsedRealtimeNanos
        lastValidFixNs = location.elapsedRealtimeNanos
        initialized = true
        return FilteredState(location.latitude, location.longitude, 0f, 0f, false)
    }

    /** Flat-Earth ENU approximation — accurate to <0.1% within 100 km of origin. */
    private fun toLocalEnu(lat: Double, lng: Double): Pair<Double, Double> {
        val cosLat = cos(Math.toRadians(originLat))
        val east = Math.toRadians(lng - originLng) * EARTH_RADIUS_M * cosLat
        val north = Math.toRadians(lat - originLat) * EARTH_RADIUS_M
        return east to north
    }

    private fun toLatLng(east: Double, north: Double): Pair<Double, Double> {
        val cosLat = cos(Math.toRadians(originLat))
        val lat = originLat + Math.toDegrees(north / EARTH_RADIUS_M)
        val lng = originLng + Math.toDegrees(east / (EARTH_RADIUS_M * cosLat))
        return lat to lng
    }

    private fun isCoasting(): Boolean {
        if (lastValidFixNs == 0L) return false
        val elapsedNs = lastTimestampNs - lastValidFixNs
        return elapsedNs / 1e9 > tunnelTimeoutSeconds
    }

    private fun currentState(coasting: Boolean): FilteredState {
        val (lat, lng) = toLatLng(x, y)
        val speed = sqrt(vx * vx + vy * vy).toFloat().coerceAtLeast(0f)
        // atan2(east, north) gives bearing from north, clockwise
        val bearing = ((Math.toDegrees(atan2(vx, vy)).toFloat()) + 360f) % 360f
        return FilteredState(lat, lng, speed, bearing, coasting)
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0
    }
}
