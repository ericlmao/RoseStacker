package dev.rosewood.rosestacker.utils;

import dev.rosewood.rosegarden.scheduler.task.ScheduledTask;
import dev.rosewood.rosegarden.utils.NMSUtil;
import dev.rosewood.rosestacker.RoseStacker;
import dev.rosewood.rosestacker.config.SettingKey;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * A single main-thread task that drains queued work under a per-tick time budget.
 * <p>
 * The periodic passes used to call {@link ThreadUtils#runOnEntity} once per entity, which on a non-Folia
 * server is one Bukkit task per entity: a burst of thousands of task submissions that the main thread
 * drains in the tick after it is submitted. That gives the plugin no ceiling at all on its worst-case
 * per-tick cost, and MSPT is decided by the worst ticks. Draining a queue under a budget gives it one by
 * construction.
 * <p>
 * This is for periodic maintenance only. Event-driven, latency-sensitive work should keep using
 * {@link ThreadUtils} directly, because anything submitted here can be delayed by a tick or more.
 * <p>
 * On Folia there is no single main thread to drain from, so {@link #submit(Entity, Runnable)} and
 * {@link #submit(Location, Runnable)} fall back to the per-entity and per-location scheduling the passes
 * used before. Callers that can batch should check {@link #isPerRegion()} and submit per entity there.
 */
public final class BatchedMainThreadExecutor {

    private static final BatchedMainThreadExecutor INSTANCE = new BatchedMainThreadExecutor();

    /**
     * A job that has waited this many ticks is run even if the tick's budget is already spent. Without it a
     * queue that grows faster than the budget drains could starve its oldest jobs indefinitely.
     */
    private static final long MAX_JOB_AGE_TICKS = 40L;

    private final Queue<Job> queue;
    private volatile long currentTick;
    private volatile boolean perRegion;
    private long budgetNanos;
    private ScheduledTask drainTask;

    private BatchedMainThreadExecutor() {
        this.queue = new ConcurrentLinkedQueue<>();
        this.perRegion = NMSUtil.isFolia();
    }

    /**
     * @return the shared executor instance
     */
    public static BatchedMainThreadExecutor getInstance() {
        return INSTANCE;
    }

    /**
     * Starts the drain task. Safe to call again to pick up a changed budget.
     */
    public void start() {
        this.stop();

        this.perRegion = NMSUtil.isFolia();
        this.budgetNanos = Math.max(0L, (long) (SettingKey.MISC_MAIN_THREAD_TASK_BUDGET.get() * 1_000_000));
        if (this.perRegion)
            return; // Folia has no single main thread; submit() routes each job to its owning region instead

        this.drainTask = RoseStacker.getInstance().getScheduler().runTaskTimer(this::drain, 1L, 1L);
    }

    /**
     * Stops the drain task, running anything still queued if this is the main thread so no pending work
     * (a partially spread autosave, for example) is dropped on shutdown.
     */
    public void stop() {
        if (this.drainTask != null) {
            this.drainTask.cancel();
            this.drainTask = null;
        }

        if (Bukkit.isPrimaryThread()) {
            Job job;
            while ((job = this.queue.poll()) != null)
                run(job);
        } else {
            this.queue.clear();
        }
    }

    /**
     * @return true if jobs are scheduled per region instead of drained from one queue, as they are on Folia
     */
    public boolean isPerRegion() {
        return this.perRegion;
    }

    /**
     * @return the number of jobs waiting to be drained
     */
    public int getQueueDepth() {
        return this.queue.size();
    }

    /**
     * Queues a job that only needs the main thread. Never blocks and never allocates a Bukkit task.
     *
     * @param job The job to run
     */
    public void submit(Runnable job) {
        if (this.perRegion) {
            ThreadUtils.runSync(job);
            return;
        }

        this.queue.add(new Job(job, this.currentTick));
    }

    /**
     * Queues a job that has to run on an entity's thread.
     *
     * @param entity The entity the job acts on
     * @param job The job to run
     */
    public void submit(Entity entity, Runnable job) {
        if (this.perRegion) {
            ThreadUtils.runOnEntity(entity, job);
            return;
        }

        this.queue.add(new Job(job, this.currentTick));
    }

    /**
     * Queues a job that has to run on a location's thread.
     *
     * @param location The location the job acts on
     * @param job The job to run
     */
    public void submit(Location location, Runnable job) {
        if (this.perRegion) {
            ThreadUtils.runOnLocation(location, job);
            return;
        }

        this.queue.add(new Job(job, this.currentTick));
    }

    private void drain() {
        long tick = this.currentTick + 1;
        this.currentTick = tick;

        if (this.queue.isEmpty())
            return;

        long deadline = System.nanoTime() + this.budgetNanos;
        Job job;
        while ((job = this.queue.poll()) != null) {
            run(job);

            if (System.nanoTime() < deadline)
                continue;

            Job next = this.queue.peek();
            // Carry the rest over to the next tick, unless the next job has been waiting too long already
            if (next == null || tick - next.submittedTick() < MAX_JOB_AGE_TICKS)
                break;
        }
    }

    private static void run(Job job) {
        try {
            job.runnable().run();
        } catch (Throwable t) {
            // One bad job must not stop the drain loop; everything queued behind it still has to run
            RoseStacker.getInstance().getLogger().log(Level.SEVERE, "An error occurred while running a batched task", t);
        }
    }

    private record Job(Runnable runnable, long submittedTick) { }

}
