package com.github.saurfang.spark.tsne

import breeze.linalg._
import breeze.stats._
import breeze.stats.distributions.Rand
import org.apache.spark.Logging
import org.apache.spark.mllib.linalg.distributed.RowMatrix
import org.apache.spark.storage.StorageLevel
import rx.lang.scala.{Subscription, Observable}

import scala.util.Random

object SimpleTSNE extends Logging {
  def tsne(
            input: RowMatrix,
            noDims: Int = 2,
            maxIterations: Int = 1000,
            perplexity: Double = 30,
            seed: Long = Random.nextLong()): Observable[(Int, DenseMatrix[Double], Double)] = {
    if(input.rows.getStorageLevel != StorageLevel.NONE) {
      logWarning("Input is not persisted and performance could be bad")
    }

    val n = input.numRows().toInt
    val early_exaggeration = 100
    val t_momentum = 250
    val initial_momentum = 0.5
    val final_momentum = 0.8
    val eta = 500.0
    val min_gain = 0.01

    val Y = DenseMatrix.rand(n, noDims, Rand.gaussian)
    val iY = DenseMatrix.zeros[Double](n, noDims)
    val gains = DenseMatrix.ones[Double](n, noDims)

    // approximate p_{j|i}
    val p_ji = X2P(input, 1e-5, perplexity)
    //logInfo(p_ji.toRowMatrix().rows.collect().toList.toString)
    // p_ij = (p_{i|j} + p_{j|i}) / 2n
    val P = p_ji.transpose().entries.union(p_ji.entries)
      .map(e => ((e.i.toInt, e.j.toInt), e.value))
      .reduceByKey(_ + _)
      .map{case ((i, j), v) => (i, (j, v / 2 / n)) }
      .groupByKey()
      .cache()

    Observable(subscriber => {
      var iteration = 1
      while(iteration <= maxIterations && !subscriber.isUnsubscribed) {
        val bcY = P.context.broadcast(Y)

        //TODO: Distribute the computation of num
        val bcNumerator = P.context.broadcast({
          val sumY = sum((Y :* Y).apply(*, ::))
          //num = 1 ./ (1 + ((-2 * (Y * Y')) .+ sum_Y)' .+ sum_Y)
          //some type inference problems here in intellij
          val num: DenseMatrix[Double] = -2.0 :* (Y * Y.t)
          num := (num(::, *) + sumY).t
          num := 1.0 :/ (1.0 :+ (num(::, *) + sumY))
          diag(num) := 0.0
          sum(num)
        })

        val (dY, loss) = P.treeAggregate((DenseMatrix.zeros[Double](n, noDims), 0.0))(
          seqOp = (c, v) => {
            // c: (grad, loss), v: (i, Iterable(j, Distance))
            val l = TSNEGradient.compute(v._2, v._1, bcY.value, bcNumerator.value, c._1, iteration < early_exaggeration)
            (c._1, c._2 + l)
          },
          combOp = (c1, c2) => {
            // c: (grad, loss)
            (c1._1 += c2._1, c1._2 + c2._2)
          })

        val momentum = if (iteration <= t_momentum) initial_momentum else final_momentum
        val dYiY = (dY :> 0.0) :!= (iY :> 0.0)
        gains := gains
          .mapPairs{ case ((i, j), gain) => if(dYiY(i, j)) gain + 0.2 else gain * 0.8 }
          .mapValues(math.max(_, min_gain))
        iY := momentum :* iY - eta :* (gains :* dY)
        Y := Y + iY
        Y := Y(*, ::) - (mean(Y(::, *)): DenseMatrix[Double]).toDenseVector

        logDebug(s"Iteration $iteration finished with $loss")
        subscriber.onNext((iteration, Y.copy, loss))
        iteration += 1
      }
      if(!subscriber.isUnsubscribed) subscriber.onCompleted()
    })
  }
}