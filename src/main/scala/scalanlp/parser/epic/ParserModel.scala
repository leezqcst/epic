package scalanlp.parser.epic

import scalanlp.parser._
import features.Feature
import projections.GrammarRefinements
import scalala.tensor.{Counter2, ::}
import scalala.library.Library
import scalanlp.trees.{BinaryRule, UnaryRule, BinarizedTree}

/**
 * Base trait for "normal" Parser-type models
 * @author dlwh
 */
trait ParserModel[L, W] extends Model[TreeInstance[L, W]] with ParserExtractable[L, W] {
  type L2 // refined label type
  type ExpectedCounts = scalanlp.parser.ExpectedCounts[Feature]
  type Inference <: ParserInference[L, W]
}

trait ParserInference[L, W] extends ProjectableInference[TreeInstance[L, W], DerivationScorer.Factory[L, W]] {
  type ExpectedCounts = scalanlp.parser.ExpectedCounts[Feature]
  type Marginal = ChartMarginal[ParseChart.LogProbabilityParseChart, L, W]

  def grammar: DerivationScorer.Factory[L, W]
  def featurizer: DerivationFeaturizer[L, W, Feature]

  def marginal(v: TreeInstance[L, W], aug: DerivationScorer.Factory[L, W]) = {
    val builder = CKYChartBuilder(grammar, ParseChart.logProb)
    val charts = builder.charts(v.words)
    charts -> charts.partition
  }

  def guessCountsFromMarginals(v: TreeInstance[L, W], marg: Marginal, aug: DerivationScorer.Factory[L, W]) = {
    marg.expectedCounts(featurizer)
  }

  def baseAugment(v: TreeInstance[L, W]) = grammar

  protected def projector: EPProjector[L, W] = new AnchoredRuleApproximator(Double.NegativeInfinity)

  def project(v: TreeInstance[L, W], m: Marginal, oldAugment: DerivationScorer.Factory[L, W]) = {
    projector.project(this, v, m)
  }
}

trait ParserModelFactory[L, W] extends ParserExtractableModelFactory[L, W] {
  type MyModel <: ParserModel[L, W]

  protected def extractBasicCounts[L, W](trees: IndexedSeq[TreeInstance[L, W]]): (Counter2[L, W, Double], Counter2[L, BinaryRule[L], Double], Counter2[L, UnaryRule[L], Double]) = {
    GenerativeParser.extractCounts(trees)
  }
}