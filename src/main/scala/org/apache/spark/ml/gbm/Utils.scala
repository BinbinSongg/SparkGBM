package org.apache.spark.ml.gbm

import java.util.Arrays

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.{classTag, ClassTag}
import scala.util.{Failure, Success}

import org.apache.hadoop.fs.Path

import org.apache.spark.Partitioner
import org.apache.spark.SparkContext
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel


/**
  * This class helps with persisting and checkpointing RDDs.
  *
  * Specifically, this abstraction automatically handles persisting and (optionally) checkpointing,
  * as well as unpersisting and removing checkpoint files.
  *
  * Users should call update() when a new Dataset has been created,
  * before the Dataset has been materialized.  After updating [[Checkpointer]], users are
  * responsible for materializing the Dataset to ensure that persisting and checkpointing actually
  * occur.
  *
  * When update() is called, this does the following:
  *  - Persist new Dataset (if not yet persisted), and put in queue of persisted Datasets.
  *  - Unpersist Datasets from queue until there are at most 3 persisted Datasets.
  *  - If using checkpointing and the checkpoint interval has been reached,
  *     - Checkpoint the new Dataset, and put in a queue of checkpointed Datasets.
  *     - Remove older checkpoints.
  *
  * WARNINGS:
  *  - This class should NOT be copied (since copies may conflict on which Datasets should be
  * checkpointed).
  *  - This class removes checkpoint files once later Datasets have been checkpointed.
  * However, references to the older Datasets will still return isCheckpointed = true.
  *
  * @param sc                 SparkContext for the Datasets given to this checkpointer
  * @param checkpointInterval Datasets will be checkpointed at this interval.
  *                           If this interval was set as -1, then checkpointing will be disabled.
  * @param storageLevel       caching storageLevel
  * @tparam T Dataset type, such as Double
  */
private[gbm] class Checkpointer[T](val sc: SparkContext,
                                   val checkpointInterval: Int,
                                   val storageLevel: StorageLevel) extends Logging {
  require(storageLevel != StorageLevel.NONE)

  /** FIFO queue of past checkpointed Datasets */
  private val checkpointQueue = mutable.Queue[RDD[T]]()

  /** FIFO queue of past persisted Datasets */
  private val persistedQueue = mutable.Queue[RDD[T]]()

  /** Number of times [[update()]] has been called */
  private var updateCount = 0

  /**
    * Update with a new Dataset. Handle persistence and checkpointing as needed.
    * Since this handles persistence and checkpointing, this should be called before the Dataset
    * has been materialized.
    *
    * @param newData New Dataset created from previous Datasets in the lineage.
    */
  def update(newData: RDD[T]): Unit = {
    persist(newData)
    persistedQueue.enqueue(newData)
    // We try to maintain 2 Datasets in persistedQueue to support the semantics of this class:
    // Users should call [[update()]] when a new Dataset has been created,
    // before the Dataset has been materialized.
    while (persistedQueue.size > 3) {
      val dataToUnpersist = persistedQueue.dequeue()
      unpersist(dataToUnpersist)
    }
    updateCount += 1

    // Handle checkpointing (after persisting)
    if (checkpointInterval != -1 && (updateCount % checkpointInterval) == 0
      && sc.getCheckpointDir.nonEmpty) {
      // Add new checkpoint before removing old checkpoints.
      checkpoint(newData)
      checkpointQueue.enqueue(newData)
      // Remove checkpoints before the latest one.
      var canDelete = true
      while (checkpointQueue.size > 1 && canDelete) {
        // Delete the oldest checkpoint only if the next checkpoint exists.
        if (isCheckpointed(checkpointQueue.head)) {
          removeCheckpointFile()
        } else {
          canDelete = false
        }
      }
    }
  }

  /** Checkpoint the Dataset */
  protected def checkpoint(data: RDD[T]): Unit = {
    data.checkpoint()
  }

  /** Return true iff the Dataset is checkpointed */
  protected def isCheckpointed(data: RDD[T]): Boolean = {
    data.isCheckpointed
  }

  /**
    * Persist the Dataset.
    * Note: This should handle checking the current [[StorageLevel]] of the Dataset.
    */
  protected def persist(data: RDD[T]): Unit = {
    if (data.getStorageLevel == StorageLevel.NONE) {
      data.persist(storageLevel)
    }
  }

  /** Unpersist the Dataset */
  protected def unpersist(data: RDD[T]): Unit = {
    data.unpersist(blocking = false)
  }

  /** Get list of checkpoint files for this given Dataset */
  protected def getCheckpointFiles(data: RDD[T]): Iterable[String] = {
    data.getCheckpointFile.map(x => x)
  }

  /**
    * Call this to unpersist the Dataset.
    */
  def unpersistDataSet(): Unit = {
    while (persistedQueue.nonEmpty) {
      val dataToUnpersist = persistedQueue.dequeue()
      unpersist(dataToUnpersist)
    }
  }

  /**
    * Call this at the end to delete any remaining checkpoint files.
    */
  def deleteAllCheckpoints(): Unit = {
    while (checkpointQueue.nonEmpty) {
      removeCheckpointFile()
    }
  }

  /**
    * Call this at the end to delete any remaining checkpoint files, except for the last checkpoint.
    * Note that there may not be any checkpoints at all.
    */
  def deleteAllCheckpointsButLast(): Unit = {
    while (checkpointQueue.size > 1) {
      removeCheckpointFile()
    }
  }

  /**
    * Get all current checkpoint files.
    * This is useful in combination with [[deleteAllCheckpointsButLast()]].
    */
  def getAllCheckpointFiles: Array[String] = {
    checkpointQueue.flatMap(getCheckpointFiles).toArray
  }

  /**
    * Dequeue the oldest checkpointed Dataset, and remove its checkpoint files.
    * This prints a warning but does not fail if the files cannot be removed.
    */
  private def removeCheckpointFile(): Unit = {
    val old = checkpointQueue.dequeue()

    // Since the old checkpoint is not deleted by Spark, we manually delete it
    getCheckpointFiles(old).foreach { file =>
      Future {
        val start = System.nanoTime
        val path = new Path(file)
        val fs = path.getFileSystem(sc.hadoopConfiguration)
        fs.delete(path, true)
        (System.nanoTime - start) / 1e9

      }.onComplete {
        case Success(v) =>
          logWarning(s"successfully remove old checkpoint file: $file, duration $v seconds")

        case Failure(t) =>
          logWarning(s"fail to remove old checkpoint file: $file, ${t.toString}")
      }
    }
  }
}


/**
  * Partitioner that partitions data according to the given ranges
  *
  * @param splits splitting points
  * @tparam K
  */
private[gbm] class GBMRangePartitioner[K: Ordering : ClassTag](val splits: Array[K]) extends Partitioner {

  {
    require(splits.nonEmpty)
    val ordering = implicitly[Ordering[K]]
    var i = 0
    while (i < splits.length - 1) {
      require(ordering.lt(splits(i), splits(i + 1)))
      i += 1
    }
  }

  private val ordering = implicitly[Ordering[K]]

  private val binarySearch = Utils.makeBinarySearch[K]

  private val search = {
    if (splits.length <= 128) {
      (array: Array[K], k: K) =>
        var i = 0
        while (i < array.length && ordering.gt(k, array(i))) {
          i += 1
        }
        i
    } else {
      (array: Array[K], k: K) =>
        var i = binarySearch(array, k)
        if (i < 0) {
          i = -i - 1
        }
        math.min(i, splits.length)
    }
  }

  override def numPartitions: Int = splits.length + 1

  override def getPartition(key: Any): Int = {
    val k = key.asInstanceOf[K]
    search(splits, k)
  }
}

private[gbm] object Utils {

  def makeBinarySearch[K: Ordering : ClassTag]: (Array[K], K) => Int = {
    // For primitive keys, we can use the natural ordering. Otherwise, use the Ordering comparator.
    classTag[K] match {
      case ClassTag.Float =>
        (l, x) => Arrays.binarySearch(l.asInstanceOf[Array[Float]], x.asInstanceOf[Float])
      case ClassTag.Double =>
        (l, x) => Arrays.binarySearch(l.asInstanceOf[Array[Double]], x.asInstanceOf[Double])
      case ClassTag.Byte =>
        (l, x) => Arrays.binarySearch(l.asInstanceOf[Array[Byte]], x.asInstanceOf[Byte])
      case ClassTag.Char =>
        (l, x) => Arrays.binarySearch(l.asInstanceOf[Array[Char]], x.asInstanceOf[Char])
      case ClassTag.Short =>
        (l, x) => Arrays.binarySearch(l.asInstanceOf[Array[Short]], x.asInstanceOf[Short])
      case ClassTag.Int =>
        (l, x) => Arrays.binarySearch(l.asInstanceOf[Array[Int]], x.asInstanceOf[Int])
      case ClassTag.Long =>
        (l, x) => Arrays.binarySearch(l.asInstanceOf[Array[Long]], x.asInstanceOf[Long])
      case _ =>
        val comparator = implicitly[Ordering[K]].asInstanceOf[java.util.Comparator[Any]]
        (l, x) => Arrays.binarySearch(l.asInstanceOf[Array[AnyRef]], x, comparator)
    }
  }
}


private trait FromDouble[H] extends Serializable {
  def fromDouble(value: Double): H
}


private object DoubleFromDouble extends FromDouble[Double] {
  override def fromDouble(value: Double): Double = value
}


private object FloatFromDouble extends FromDouble[Float] {
  override def fromDouble(value: Double): Float = value.toFloat
}


private object DecimalFromDouble extends FromDouble[BigDecimal] {
  override def fromDouble(value: Double): BigDecimal = BigDecimal(value)
}


private[gbm] object FromDouble {

  implicit final val doubleFromDouble: FromDouble[Double] = DoubleFromDouble

  implicit final val floatFromDouble: FromDouble[Float] = FloatFromDouble

  implicit final val decimalFromDouble: FromDouble[BigDecimal] = DecimalFromDouble
}
