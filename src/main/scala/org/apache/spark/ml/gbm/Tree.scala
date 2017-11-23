package org.apache.spark.ml.gbm

import scala.collection.mutable
import scala.reflect.ClassTag

import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD

private[gbm] object Tree extends Logging {

  /**
    * Implementation of training a new tree
    *
    * @param data        instances containing (grad, hess, bins)
    * @param boostConfig boosting configuration
    * @param treeConfig  tree growing configuration
    * @tparam H type of gradient and hessian
    * @tparam B type of bin
    * @return a new tree if any
    */
  def train[H: Numeric : ClassTag, B: Integral : ClassTag](data: RDD[(H, H, Array[B])],
                                                           boostConfig: BoostConfig,
                                                           treeConfig: TreeConfig): Option[TreeModel] = {
    val sc = data.sparkContext
    val numExecutors = sc.getExecutorMemoryStatus.size

    data.persist(boostConfig.getStorageLevel)

    val root = LearningNode.create(1L)

    var minNodeId = 1L
    var numLeaves = 1L
    var finished = false

    if (root.subtreeDepth >= boostConfig.getMaxDepth) {
      finished = true
    }
    if (numLeaves >= boostConfig.getMaxLeaves) {
      finished = true
    }

    var nodeIds = sc.emptyRDD[Long]
    val nodeIdsCheckpointer = new Checkpointer[Long](sc,
      boostConfig.getCheckpointInterval, boostConfig.getStorageLevel)

    var hists = sc.emptyRDD[((Long, Int), Array[H])]
    val histsCheckpointer = new Checkpointer[((Long, Int), Array[H])](sc,
      boostConfig.getCheckpointInterval, boostConfig.getStorageLevel)

    val logPrefix = s"Iter ${treeConfig.iteration}: Tree ${treeConfig.treeIndex}:"
    logWarning(s"$logPrefix tree building start")

    val lastSplits = mutable.Map[Long, Split]()


    while (!finished) {
      val start = System.nanoTime

      val parallelism = computeParallelism(numExecutors, math.max(lastSplits.size * 2, 1),
        treeConfig.numCols, boostConfig.getColSampleByLevel)

      val depth = root.subtreeDepth
      logWarning(s"$logPrefix Depth $depth: splitting start, parallelism $parallelism")

      if (minNodeId == 1L) {
        nodeIds = data.map(_ => 1L)
      } else {
        nodeIds = updateNodeIds[H, B](data, nodeIds, lastSplits.toMap)
      }
      nodeIdsCheckpointer.update(nodeIds)

      if (minNodeId == 1) {
        hists = computeHists[H, B](data.zip(nodeIds), minNodeId, parallelism)
      } else {
        val leftHists = computeHists[H, B](data.zip(nodeIds), minNodeId, parallelism)
        hists = subtractHists[H](hists, leftHists, boostConfig.getMinNodeHess, parallelism)
      }
      histsCheckpointer.update(hists)

      val seed = boostConfig.getSeed + treeConfig.treeIndex + depth
      val splits = findSplits[H](hists, boostConfig, treeConfig, seed)

      if (splits.isEmpty) {
        logWarning(s"$logPrefix Depth $depth: no more splits found, tree building finished")
        finished = true

      } else if (numLeaves + splits.size > boostConfig.getMaxLeaves) {
        logWarning(s"$logPrefix Depth $depth: maxLeaves=${boostConfig.getMaxLeaves} reached, tree building finished")
        finished = true

      } else {
        logWarning(s"$logPrefix Depth $depth: splitting finished, ${splits.size}/$numLeaves leaves split, " +
          s"gain=${splits.values.map(_.gain).sum}, duration ${(System.nanoTime - start) / 1e9} seconds")

        numLeaves += splits.size

        val nodes = root.nodeIterator.filter { node =>
          node.nodeId >= minNodeId && splits.contains(node.nodeId)
        }.toArray

        /** update tree */
        nodes.foreach { node =>
          node.isLeaf = false
          node.split = splits.get(node.nodeId)

          val leftId = node.nodeId << 1
          node.leftNode = Some(LearningNode.create(leftId))
          node.leftNode.get.prediction = node.split.get.leftWeight

          val rightId = leftId + 1
          node.rightNode = Some(LearningNode.create(rightId))
          node.rightNode.get.prediction = node.split.get.rightWeight
        }

        /** update last splits */
        lastSplits.clear()
        splits.foreach { case (nodeId, split) =>
          lastSplits.update(nodeId, split)
        }
      }

      if (root.subtreeDepth >= boostConfig.getMaxDepth) {
        logWarning(s"$logPrefix maxDepth=${boostConfig.getMaxDepth} reached, tree building finished")
        finished = true
      }
      if (numLeaves >= boostConfig.getMaxLeaves) {
        logWarning(s"$logPrefix maxLeaves=${boostConfig.getMaxLeaves} reached, tree building finished")
        finished = true
      }

      minNodeId <<= 1
    }
    logWarning(s"$logPrefix tree building finished")

    data.unpersist(blocking = false)
    nodeIdsCheckpointer.deleteAllCheckpoints()
    nodeIdsCheckpointer.unpersistDataSet()
    histsCheckpointer.deleteAllCheckpoints()
    histsCheckpointer.unpersistDataSet()

    if (root.subtreeDepth > 0) {
      Some(TreeModel.createModel(root, boostConfig, treeConfig))
    } else {
      None
    }
  }


  /**
    * update nodeIds
    *
    * @param data       instances containing (grad, hess, bins)
    * @param nodeIds    previous nodeIds
    * @param lastSplits splits found in the last round
    * @tparam H
    * @tparam B
    * @return updated nodeIds
    */
  def updateNodeIds[H: Numeric : ClassTag, B: Integral : ClassTag](data: RDD[(H, H, Array[B])],
                                                                   nodeIds: RDD[Long],
                                                                   lastSplits: Map[Long, Split]): RDD[Long] = {
    data.zip(nodeIds).map {
      case ((_, _, bins), nodeId) =>
        val split = lastSplits.get(nodeId)
        if (split.nonEmpty) {
          val leftNodeId = nodeId << 1
          if (split.get.goLeft[B](bins)) {
            leftNodeId
          } else {
            leftNodeId + 1
          }
        } else {
          nodeId
        }
    }
  }


  /**
    * Compute the histogram of root node or the left leaves with nodeId greater than minNodeId
    *
    * @param data        instances appended with nodeId, containing ((grad, hess, bins), nodeId)
    * @param minNodeId   minimum nodeId for this level
    * @param parallelism parallelism
    * @tparam H
    * @tparam B
    * @return histogram data containing (nodeId, columnId, histogram)
    */
  def computeHists[H: Numeric : ClassTag, B: Integral : ClassTag](data: RDD[((H, H, Array[B]), Long)],
                                                                  minNodeId: Long,
                                                                  parallelism: Int): RDD[((Long, Int), Array[H])] = {
    val intB = implicitly[Integral[B]]
    val numH = implicitly[Numeric[H]]

    data.filter { case (_, nodeId) =>
      (nodeId >= minNodeId && nodeId % 2 == 0) || nodeId == 1

    }.flatMap { case ((grad, hess, bins), nodeId) =>
      bins.zipWithIndex.map { case (bin, featureId) =>
        ((nodeId, featureId), (bin, grad, hess))
      }

    }.aggregateByKey[Array[H]](Array.empty[H], parallelism)(
      seqOp = {
        case (hist, (bin, grad, hess)) =>
          val index = intB.toInt(bin) << 1

          if (hist.length < index + 2) {
            val newHist = hist ++ Array.fill(index + 2 - hist.length)(numH.zero)
            newHist(index) = grad
            newHist(index + 1) = hess
            newHist
          } else {
            hist(index) = numH.plus(hist(index), grad)
            hist(index + 1) = numH.plus(hist(index + 1), hess)
            hist
          }

      }, combOp = {
        case (hist1, hist2) if hist1.length >= hist2.length =>
          var i = 0
          while (i < hist2.length) {
            hist1(i) = numH.plus(hist1(i), hist2(i))
            i += 1
          }
          hist1

        case (hist1, hist2) =>
          var i = 0
          while (i < hist1.length) {
            hist2(i) = numH.plus(hist1(i), hist2(i))
            i += 1
          }
          hist2
      })
  }


  /**
    * Histogram subtraction
    *
    * @param nodeHists   histogram data of parent nodes
    * @param leftHists   histogram data of left leaves
    * @param minNodeHess minimum hess needed for a node
    * @param parallelism parallelism
    * @tparam H
    * @return histogram data of both left and right leaves
    */
  def subtractHists[H: Numeric : ClassTag](nodeHists: RDD[((Long, Int), Array[H])],
                                           leftHists: RDD[((Long, Int), Array[H])],
                                           minNodeHess: Double,
                                           parallelism: Int): RDD[((Long, Int), Array[H])] = {
    val numH = implicitly[Numeric[H]]

    leftHists.map { case ((nodeId, featureId), hist) =>
      ((nodeId >> 1, featureId), hist)

    }.join(nodeHists, parallelism)

      .flatMap { case ((nodeId, featureId), (leftHist, nodeHist)) =>
        require(leftHist.length <= nodeHist.length)

        var i = 0
        while (i < leftHist.length) {
          nodeHist(i) = numH.minus(nodeHist(i), leftHist(i))
          i += 1
        }

        val leftNodeId = nodeId << 1
        val rightNodeId = leftNodeId + 1

        ((leftNodeId, featureId), leftHist) ::
          ((rightNodeId, featureId), nodeHist) :: Nil

      }.filter { case ((_, _), hist) =>

      var hessSum = 0.0
      var nnz = 0
      var i = 0
      while (i < hist.length) {
        if (hist(i) != 0 || hist(i + 1) != 0) {
          hessSum += numH.toDouble(hist(i + 1))
          nnz += 1
        }
        i += 2
      }

      /** leaves with hess no more than minNodeHess * 2 can not grow */
      nnz >= 2 && hessSum >= minNodeHess * 2
    }
  }


  /**
    * Search the optimal splits on each leaves
    *
    * @param nodeHists   histogram data of leaves nodes
    * @param boostConfig boosting configuration
    * @param treeConfig  tree growing configuration
    * @param seed        random seed for column sampling by level
    * @tparam H
    * @return optimal splits for each node
    */
  def findSplits[H: Numeric : ClassTag](nodeHists: RDD[((Long, Int), Array[H])],
                                        boostConfig: BoostConfig,
                                        treeConfig: TreeConfig,
                                        seed: Long): Map[Long, Split] = {

    /** column sampling by level */
    val sampledHists = if (boostConfig.getColSampleByLevel == 1) {
      nodeHists
    } else {
      nodeHists.sample(false, boostConfig.getColSampleByLevel, seed)
    }

    sampledHists.flatMap {
      case ((nodeId, featureId), hist) =>
        val split = Split.split[H](featureId, hist, boostConfig, treeConfig)
        split.map((nodeId, _))

    }.mapPartitions { it =>
      val splits = mutable.Map[Long, Split]()
      it.foreach { case (nodeId, split) =>
        val s = splits.get(nodeId)
        if (s.isEmpty || split.gain > s.get.gain) {
          splits.update(nodeId, split)
        }
      }
      Iterator.single(splits.toArray)

    }.treeReduce(
      f = {
        case (splits1, splits2) =>
          (splits1 ++ splits2).groupBy(_._1)
            .map { case (nodeId, splits) =>
              (nodeId, splits.map(_._2).maxBy(_.gain))
            }.toArray
      }, depth = boostConfig.getAggregationDepth)
      .toMap
  }


  /**
    * Compute parallelism of splitting
    *
    * @param numExecutors     number of executors
    * @param numLeaves        number of leaves in current level
    * @param numCols          number of columns
    * @param colSampleByLevel rate of column sampling by level
    * @return parallelism of splitting
    */
  def computeParallelism(numExecutors: Int,
                         numLeaves: Int,
                         numCols: Int,
                         colSampleByLevel: Double): Int = {
    require(numExecutors > 0 && numLeaves > 0 && numCols > 0)

    if (numExecutors == 1) {
      /** driver only */
      1

    } else {
      /** approximate number of histogram in current level */
      val approxCount = numLeaves * numCols * colSampleByLevel

      var k = (approxCount / (numExecutors - 1)).ceil.toInt

      k = math.min(k, 128)

      k = math.max(k, 1)

      k * (numExecutors - 1)
    }
  }
}

class TreeModel(val root: Node) extends Serializable {

  lazy val depth: Int = root.subtreeDepth

  lazy val numLeaves: Long = root.numLeaves

  lazy val numNodes: Long = root.numDescendants

  def predict[B: Integral](bins: Array[B]): Double = root.predict(bins)

  def index[B: Integral](bins: Array[B]): Long = root.index(bins)

  def computeImportance: Map[Int, Double] = {
    val gains = collection.mutable.Map[Int, Double]()
    root.nodeIterator.foreach {
      case n: InternalNode =>
        val gain = gains.getOrElse(n.featureId, 0.0)
        gains.update(n.featureId, gain + n.gain)

      case _ =>
    }

    gains.toMap
  }
}


private[gbm] object TreeModel {

  def createModel(root: LearningNode,
                  boostMeta: BoostConfig,
                  treeMeta: TreeConfig): TreeModel = {
    val leafIds = root.nodeIterator.filter(_.isLeaf).map(_.nodeId).toArray.sorted
    val node = TreeModel.createNode(root, treeMeta.columns, leafIds)
    new TreeModel(node)
  }

  def createNode(node: LearningNode,
                 columns: Array[Int],
                 leafIds: Array[Long]): Node = {

    if (node.isLeaf) {
      require(node.leftNode.isEmpty &&
        node.rightNode.isEmpty &&
        node.split.isEmpty)

      val leafId = leafIds.indexOf(node.nodeId)
      new LeafNode(node.prediction, leafId)

    } else {
      require(node.split.nonEmpty &&
        node.leftNode.nonEmpty && node.rightNode.nonEmpty)

      val reindex = columns(node.split.get.featureId)

      node.split.get match {
        case s: SeqSplit =>
          new InternalNode(reindex, true, s.missingGoLeft,
            Array(s.threshold), s.gain,
            createNode(node.leftNode.get, columns, leafIds),
            createNode(node.rightNode.get, columns, leafIds))

        case s: SetSplit =>
          new InternalNode(reindex, false, s.missingGoLeft,
            s.leftSet, s.gain,
            createNode(node.leftNode.get, columns, leafIds),
            createNode(node.rightNode.get, columns, leafIds))
      }
    }
  }
}
