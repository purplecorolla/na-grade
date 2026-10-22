package app.nagrade.api.setlist.setlistfm

import app.nagrade.api.music.ProviderBusyException
import app.nagrade.api.setlist.SetlistQuotaExceededException
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

// Limits of the setlist.fm key, kept in Redis so they hold for every api instance. Calls are spaced by minInterval:
// the key allows "2 per second", but answered 429 to two calls in the same second (manual test of #28). A call waits
// for its slot up to maxWait; over the daily quota it is refused without calling setlist.fm
@Component
class SetlistFmRateLimiter(
    private val redis: StringRedisTemplate,
    @Value("\${app.setlistfm.min-interval}") private val minInterval: Duration,
    @Value("\${app.setlistfm.daily-quota}") private val dailyQuota: Long,
    @Value("\${app.setlistfm.max-wait}") private val maxWait: Duration,
) {
    private val clock = Clock.systemUTC()

    private val reserveSlot = DefaultRedisScript(RESERVE_SLOT, Long::class.javaObjectType)

    fun acquire() {
        waitForSlot()
        // setlist.fm doesn't say when the day resets: UTC, with a margin in the quota
        if (increment("setlistfm:quota:${LocalDate.now(clock)}", Duration.ofDays(2)) > dailyQuota) {
            throw SetlistQuotaExceededException(SOURCE)
        }
    }

    private fun waitForSlot() {
        val wait = redis.execute(reserveSlot, listOf(NEXT_SLOT), minInterval.toMillis().toString(), maxWait.toMillis().toString())
        if (wait == null || wait < 0) throw ProviderBusyException(SOURCE)
        if (wait > 0) Thread.sleep(wait)
    }

    private fun increment(key: String, ttl: Duration): Long {
        val value = redis.opsForValue().increment(key) ?: 0
        if (value == 1L) redis.expire(key, ttl)
        return value
    }

    private companion object {
        const val SOURCE = "setlistfm"
        const val NEXT_SLOT = "setlistfm:next-slot"

        // Atomic, so two instances never get the same slot. Uses the Redis clock, the same for every instance.
        // Returns how long to wait for the reserved slot, in ms, or -1 when that would be longer than maxWait
        val RESERVE_SLOT = """
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local interval = tonumber(ARGV[1])
            local maxWait = tonumber(ARGV[2])
            local slot = math.max(now, tonumber(redis.call('GET', KEYS[1]) or '0'))
            local wait = slot - now
            if wait > maxWait then return -1 end
            redis.call('SET', KEYS[1], slot + interval, 'PX', wait + interval)
            return wait
        """.trimIndent()
    }
}
