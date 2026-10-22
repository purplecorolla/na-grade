package app.nagrade.api.setlist.setlistfm

import app.nagrade.api.music.ProviderBusyException
import app.nagrade.api.setlist.SetlistQuotaExceededException
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

// Limits of the setlist.fm key, counted in Redis so they hold for every api instance. Over the per-second limit
// the call waits for the next second, up to maxWait; over the daily quota it is refused without calling setlist.fm
@Component
class SetlistFmRateLimiter(
    private val redis: StringRedisTemplate,
    @Value("\${app.setlistfm.requests-per-second}") private val requestsPerSecond: Long,
    @Value("\${app.setlistfm.daily-quota}") private val dailyQuota: Long,
    @Value("\${app.setlistfm.max-wait}") private val maxWait: Duration,
) {
    private val clock = Clock.systemUTC()

    fun acquire() {
        waitForSlot()
        // setlist.fm doesn't say when the day resets: UTC, with a margin in the quota
        if (increment("setlistfm:quota:${LocalDate.now(clock)}", Duration.ofDays(2)) > dailyQuota) {
            throw SetlistQuotaExceededException(SOURCE)
        }
    }

    private fun waitForSlot() {
        val deadline = clock.millis() + maxWait.toMillis()
        while (true) {
            val second = clock.instant().epochSecond
            if (increment("setlistfm:rate:$second", Duration.ofSeconds(2)) <= requestsPerSecond) return
            val nextSecond = (second + 1) * 1000
            if (nextSecond > deadline) throw ProviderBusyException(SOURCE)
            Thread.sleep((nextSecond - clock.millis()).coerceAtLeast(0))
        }
    }

    private fun increment(key: String, ttl: Duration): Long {
        val value = redis.opsForValue().increment(key) ?: 0
        if (value == 1L) redis.expire(key, ttl)
        return value
    }

    private companion object {
        const val SOURCE = "setlistfm"
    }
}
