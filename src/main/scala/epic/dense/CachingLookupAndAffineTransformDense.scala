package epic.dense

import scala.runtime.ScalaRunTime
import breeze.linalg._
import epic.features.SegmentedIndex
import epic.framework.Feature
import scala.collection.mutable.HashMap

/**
 * Used at the input layer to cache lookups and 
 */
case class CachingLookupAndAffineTransformDense[FV](numOutputs: Int,
                                                    numInputs: Int,
                                                    word2vecFeaturizer: Word2VecSurfaceFeaturizerIndexed[String],
                                                    includeBias: Boolean = true) extends Transform[Array[Int], DenseVector[Double]] {


  val index = new CachingLookupAndAffineTransformDense.Index(numOutputs, numInputs, includeBias)
  
  def extractLayer(weights: DenseVector[Double]) = {
    val mat = weights(0 until (numOutputs * numInputs)).asDenseMatrix.reshape(numOutputs, numInputs, view = View.Require)
    val bias = if(includeBias) {
      weights(numOutputs * numInputs until index.size)
    } else {
      DenseVector.zeros[Double](numOutputs)
    }
    new Layer(mat, bias)
  }

  case class Layer(weights: DenseMatrix[Double], bias: DenseVector[Double]) extends Transform.Layer[Array[Int],DenseVector[Double]] {
    
    override val index = CachingLookupAndAffineTransformDense.this.index
    
    val weightst = weights.t

    val cache = new HashMap[(Int,Int),DenseVector[Double]]

    def activations(fv: Array[Int]) = {
      val finalVector = DenseVector.zeros[Double](numOutputs)
      for (i <- 0 until fv.size) {
        val wordPosn = fv(i) -> i
        if (!cache.contains(wordPosn)) {
          cache.put(wordPosn, weights(i to i+numOutputs, ::) * DenseVector(word2vecFeaturizer.word2vec(wordPosn._1)))
        }
        finalVector += cache(wordPosn)
      }
      finalVector + bias
    }

    def tallyDerivative(deriv: DenseVector[Double], _scale: =>Vector[Double], fv: Array[Int]) = {
      val scale = _scale
      val matDeriv = deriv(0 until (numOutputs * numInputs)).asDenseMatrix.reshape(numOutputs, numInputs, view = View.Require)
      val biasDeriv = if(includeBias) {
        deriv(numOutputs * numInputs until index.size)
      } else {
        DenseVector.zeros[Double](numOutputs)
      }

      // whole function is f(mat * inner(fv) + bias)
      // scale(i) pushes in  (f'(mat * inner(v) + bias))(i)
      val innerAct = DenseVector(word2vecFeaturizer.convertToVector(fv));
      
      // d/d(weights(::, i)) == scale(i) * innerAct
      for (i <- 0 until weights.rows) {
        val a: Double = scale(i)
        if(a != 0.0) {
          axpy(a, innerAct, matDeriv.t(::, i))
        // so d/dbias(i) = scale(i)
          biasDeriv(i) += a
        }
      }

//      biasDeriv += scale

      // scale is f'(mat * inner(v) + bias)
      // d/dv is mat.t * f'(mat * inner(v) + bias)
    }
  }
}

object CachingLookupAndAffineTransformDense {
  
  case class Index(numOutputs: Int, numInputs: Int, includeBias: Boolean = true) extends breeze.util.Index[Feature] {
    def apply(t: Feature): Int = t match {
      case NeuralFeature(output, input) if output < numOutputs && input < numInputs && output > 0 && input > 0 =>
        output * numInputs + input
      case NeuralBias(output) if output <= numOutputs => output + numOutputs * numInputs
      case _ => -1
    }

    def unapply(i: Int): Option[Feature] = {
      if (i < 0 || i >= size) {
        None
      } else if (includeBias && i >= numInputs * numOutputs) {
        Some(NeuralBias(i - numInputs * numOutputs))
      } else  {
        Some(NeuralFeature(i/numInputs, i % numInputs))
      }
    }

    def makeMatrix(dv: DenseVector[Double]):DenseMatrix[Double] = {
      assert(dv.stride == 1)
      new DenseMatrix(numOutputs, numInputs, dv.data, dv.offset)
    }

    def pairs: Iterator[(Feature, Int)] = iterator.zipWithIndex

    def iterator: Iterator[Feature] = Iterator.range(0, size) map unapply map (_.get)

    override val size: Int = if(includeBias) numOutputs * numInputs + numOutputs else numOutputs * numInputs

    override def toString() = ScalaRunTime._toString(this)
  }
}