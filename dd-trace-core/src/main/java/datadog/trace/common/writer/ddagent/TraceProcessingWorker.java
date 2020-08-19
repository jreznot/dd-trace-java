package datadog.trace.common.writer.ddagent;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.common.exec.CommonTaskExecutor;
import datadog.common.exec.DaemonThreadFactory;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.Monitor;
import datadog.trace.core.processor.TraceProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

/**
 * Worker which applies rules to traces and serializes the results. Upon completion, the serialized
 * traces are published in batches to the Datadog Agent}.
 *
 * <p>publishing to the buffer will not block the calling thread, but instead will return false if
 * the buffer is full. This is to avoid impacting an application thread.
 */
@Slf4j
public class TraceProcessingWorker implements AutoCloseable {

  // empty list used to signal heartbeat, which means we could spuriously flush
  // if an empty list were published upstream, but care is taken in PendingTrace
  // and CoreTracer not to do this.
  private static final List<List<DDSpan>> HEARTBEAT = new ArrayList<>(0);

  private final PrioritizationStrategy prioritizationStrategy;
  private final MpscBlockingConsumerArrayQueue<Object> primaryQueue;
  private final MpscBlockingConsumerArrayQueue<Object> secondaryQueue;
  private final TraceSerializingHandler serializingHandler;
  private final Thread serializerThread;
  private final boolean doHeartbeat;
  private final int capacity;

  private volatile ScheduledFuture<?> heartbeat;

  public TraceProcessingWorker(
      final int capacity,
      final Monitor monitor,
      final PayloadDispatcher dispatcher,
      final Prioritization prioritization,
      final long flushInterval,
      final TimeUnit timeUnit,
      final boolean heartbeat) {
    this(
        capacity,
        monitor,
        dispatcher,
        new TraceProcessor(),
        prioritization,
        flushInterval,
        timeUnit,
        heartbeat);
  }

  public TraceProcessingWorker(
      final int capacity,
      final Monitor monitor,
      final PayloadDispatcher dispatcher,
      final TraceProcessor processor,
      final Prioritization prioritization,
      final long flushInterval,
      final TimeUnit timeUnit,
      final boolean heartbeat) {
    this.doHeartbeat = heartbeat;
    this.capacity = capacity;
    this.primaryQueue = createQueue(capacity);
    this.secondaryQueue = createQueue(capacity);
    this.prioritizationStrategy = prioritization.create(primaryQueue, secondaryQueue);
    this.serializingHandler =
        new TraceSerializingHandler(
            primaryQueue, secondaryQueue, monitor, processor, flushInterval, timeUnit, dispatcher);
    this.serializerThread = DaemonThreadFactory.TRACE_PROCESSOR.newThread(serializingHandler);
  }

  public void start() {
    if (doHeartbeat) {
      // This provides a steady stream of events to enable flushing with a low throughput.
      heartbeat =
          CommonTaskExecutor.INSTANCE.scheduleAtFixedRate(
              new HeartbeatTask(), this, 1000, 1000, MILLISECONDS, "disruptor heartbeat");
    }
    this.serializerThread.start();
  }

  public boolean flush(long timeout, TimeUnit timeUnit) {
    CountDownLatch latch = new CountDownLatch(1);
    FlushEvent flush = new FlushEvent(latch);
    boolean offered;
    do {
      offered = primaryQueue.offer(flush);
    } while (!offered);
    try {
      return latch.await(timeout, timeUnit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() {
    if (null != heartbeat) {
      heartbeat.cancel(true);
    }
    serializerThread.interrupt();
  }

  public boolean publish(int samplingPriority, final List<DDSpan> trace) {
    return prioritizationStrategy.publish(samplingPriority, trace);
  }

  void heartbeat() {
    // if we don't insist on publishing a heartbeat, they might get starved out
    // if traces are very small, it might take quite a long time to fill the buffer,
    // without regular heartbeats
    boolean success;
    do {
      success = primaryQueue.offer(HEARTBEAT);
    } while (!success);
  }

  public int getCapacity() {
    return capacity;
  }

  public long getRemainingCapacity() {
    // only advertise primary capacity (partly to keep test which aims to saturate the queue happy)
    return primaryQueue.remainingCapacity();
  }

  private static MpscBlockingConsumerArrayQueue<Object> createQueue(int capacity) {
    return new MpscBlockingConsumerArrayQueue<>(capacity);
  }

  public static class TraceSerializingHandler
      implements Runnable, MessagePassingQueue.Consumer<Object> {

    private final MpscBlockingConsumerArrayQueue<Object> primaryQueue;
    private final MpscBlockingConsumerArrayQueue<Object> secondaryQueue;
    private final TraceProcessor processor;
    private final Monitor monitor;
    private final long ticksRequiredToFlush;
    private final boolean doTimeFlush;
    private final PayloadDispatcher payloadDispatcher;
    private long lastTicks;

    public TraceSerializingHandler(
        final MpscBlockingConsumerArrayQueue<Object> primaryQueue,
        final MpscBlockingConsumerArrayQueue<Object> secondaryQueue,
        final Monitor monitor,
        final TraceProcessor traceProcessor,
        final long flushInterval,
        final TimeUnit timeUnit,
        final PayloadDispatcher payloadDispatcher) {
      this.primaryQueue = primaryQueue;
      this.secondaryQueue = secondaryQueue;
      this.monitor = monitor;
      this.processor = traceProcessor;
      this.doTimeFlush = flushInterval > 0;
      this.payloadDispatcher = payloadDispatcher;
      if (doTimeFlush) {
        this.lastTicks = System.nanoTime();
        this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);
      } else {
        this.ticksRequiredToFlush = Long.MAX_VALUE;
      }
    }

    @SuppressWarnings("unchecked")
    public void onEvent(Object event) {
      // publish an incomplete batch if
      // 1. we get a heartbeat, and it's time to send (early heartbeats will be ignored)
      // 2. a synchronous flush command is received (at shutdown)
      try {
        if (event instanceof List) {
          List<DDSpan> trace = (List<DDSpan>) event;
          if (trace.isEmpty()) { // a heartbeat
            if (shouldFlush()) {
              payloadDispatcher.flush();
            }
          } else {
            // TODO populate `_sample_rate` metric in a way that accounts for lost/dropped traces
            payloadDispatcher.addTrace(processor.onTraceComplete(trace));
          }
        } else if (event instanceof FlushEvent) {
          payloadDispatcher.flush();
          ((FlushEvent) event).sync();
        }
      } catch (final Throwable e) {
        if (log.isDebugEnabled()) {
          log.debug("Error while serializing trace", e);
        }
        List<DDSpan> data = event instanceof List ? (List<DDSpan>) event : null;
        monitor.onFailedSerialize(data, e);
      }
    }

    private boolean shouldFlush() {
      if (doTimeFlush) {
        long nanoTime = System.nanoTime();
        long ticks = nanoTime - lastTicks;
        if (ticks > ticksRequiredToFlush) {
          lastTicks = nanoTime;
          return true;
        }
      }
      return false;
    }

    @Override
    public void run() {
      try {
        consume();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      log.info("datadog trace processor exited");
    }

    private void consume() throws InterruptedException {
      Thread thread = Thread.currentThread();
      while (!thread.isInterrupted()) {
        consumeFromPrimaryQueue();
        consumeFromSecondaryQueue();
      }
    }

    private void consumeFromPrimaryQueue() throws InterruptedException {
      Object event = primaryQueue.poll(100, MILLISECONDS);
      if (null != event) {
        // there's a high priority trace, consume it,
        // and then drain whatever's in the queue
        onEvent(event);
        consumeBatch(primaryQueue);
      }
    }

    private void consumeFromSecondaryQueue() {
      // if there's something there now, take it and try to fill a batch,
      // if not, it's the secondary queue so get back to polling the primary ASAP
      Object event = secondaryQueue.poll();
      if (null != event) {
        onEvent(event);
        consumeBatch(secondaryQueue);
      }
    }

    private void consumeBatch(MessagePassingQueue<Object> queue) {
      queue.drain(this, queue.size());
    }

    @Override
    public void accept(Object event) {
      onEvent(event);
    }
  }

  // Important to use explicit class to avoid implicit hard references to TraceProcessingWorker
  private static final class HeartbeatTask
      implements CommonTaskExecutor.Task<TraceProcessingWorker> {
    @Override
    public void run(final TraceProcessingWorker traceProcessor) {
      traceProcessor.heartbeat();
    }
  }
}
