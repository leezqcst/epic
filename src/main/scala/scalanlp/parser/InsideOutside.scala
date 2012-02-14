package scalanlp.parser

import scalanlp.collection.mutable.SparseArrayMap
import ParseChart._

import InsideOutside._

import math.exp
import scalala.tensor.::
import scalala.tensor.mutable.{Counter, Counter2, Vector}
import scalala.tensor.dense.DenseVector

/**
 * InsideOutside computes expected counts for rules and lexical emissions for a chart builder
 * @author dlwh
 */
class InsideOutside[L,W](val parser: ChartBuilder[LogProbabilityParseChart,L,W]) {
  def this(root: L, g: Grammar[L], lexicon: Lexicon[L,W])  = {
    this(new CKYChartBuilder[ParseChart.LogProbabilityParseChart,L,W](root,lexicon,g,logProb))
  }

  def grammar = parser.grammar
  def lexicon = parser.lexicon
  def root = parser.root

  def expectedCounts(words: Seq[W], validSpan: SpanScorer[L] = SpanScorer.identity,
                     spanVisitor: AnchoredSpanVisitor = AnchoredSpanVisitor.noOp):ExpectedCounts[W] = {
    val inside = parser.buildInsideChart(words, validSpan)
    val totalProb = inside.top.labelScore(0, words.length, root)
    val outside = parser.buildOutsideChart(inside, validSpan)

    expectedCounts(words,inside,outside, totalProb, validSpan, spanVisitor)
  }

  def expectedCounts(words: Seq[W],
                     inside: LogProbabilityParseChart[L],
                     outside: LogProbabilityParseChart[L],
                     totalProb: Double, validSpan: SpanScorer[L],
                     spanVisitor: AnchoredSpanVisitor) = {
    val wordCounts = computeWordCounts(words, inside, outside, validSpan, totalProb, spanVisitor)
    val ruleCounts = computeBinaryCounts(words, inside, outside, validSpan, totalProb, spanVisitor)
    val unaryRuleCounts = computeUnaryCounts(words, inside, outside, validSpan, totalProb, spanVisitor)

    ExpectedCounts(ruleCounts + unaryRuleCounts, wordCounts, totalProb)
  }

  private def computeWordCounts(words: scala.Seq[W],
                                inside: LogProbabilityParseChart[L],
                                outside: LogProbabilityParseChart[L],
                                validSpan: SpanScorer[L],
                                totalProb: Double,
                                spanVisitor: AnchoredSpanVisitor): SparseArrayMap[Counter[W, Double]] = {
    val wordCounts = grammar.labelEncoder.fillSparseArrayMap(Counter[W, Double]())
    // handle lexical productions:
    for (i <- 0 until words.length) {
      val w = words(i)
      for (l <- inside.bot.enteredLabelIndexes(i, i + 1) if isTag(l)) {
        val iScore = inside.bot.labelScore(i, i + 1, l)
        val oScore = outside.bot.labelScore(i, i + 1, l)
        val count = exp(iScore + oScore - totalProb)
        if(spanVisitor ne AnchoredSpanVisitor.noOp) spanVisitor.visitSpan(i,i+1,l,count)
        wordCounts.getOrElseUpdate(l)(w) += count
      }
    }
    wordCounts
  }

  private def computeBinaryCounts(words: scala.Seq[W],
                                  inside: LogProbabilityParseChart[L],
                                  outside: LogProbabilityParseChart[L],
                                  validSpan: SpanScorer[L],
                                  totalProb: Double,
                                  spanVisitor: AnchoredSpanVisitor = AnchoredSpanVisitor.noOp) = {
    val ruleCounts = grammar.mkDenseVector()
    // handle binary rules
    for{
      span <- 2 to words.length
      begin <- 0 to (words.length - span)
      end = begin + span
      a <- inside.bot.enteredLabelIndexes(begin,end)
    } {
      var i = 0;
      val rules = grammar.indexedBinaryRulesWithParent(a)
      val spanScore = validSpan.scoreSpan(begin,end,a)
      while(i < rules.length) {
        val r = rules(i)
        val b = grammar.leftChild(r)
        val c = grammar.rightChild(r)
        val ruleScore = grammar.ruleScore(r)
        i += 1
        val feasibleSpan = inside.top.feasibleSpanX(begin, end, b, c)
        var split = (feasibleSpan >> 32).toInt
        val endSplit = feasibleSpan.toInt // lower 32 bits
        var selfScore = 0.0
        while(split < endSplit) {
          val bScore = inside.top.labelScore(begin, split, b)
          val cScore = inside.top.labelScore(split, end, c)
          val aScore = outside.bot.labelScore(begin, end, a)
          val rScore = ruleScore + validSpan.scoreBinaryRule(begin,split,end,r) + spanScore
          val prob = exp(bScore + cScore + aScore + rScore - totalProb)
          if(spanVisitor ne AnchoredSpanVisitor.noOp) spanVisitor.visitBinaryRule(begin,split,end,r,prob)
          if(prob != 0.0) {
            selfScore += prob
          }
          split += 1
        }
        ruleCounts(r) += selfScore
        if(spanVisitor ne AnchoredSpanVisitor.noOp) spanVisitor.visitSpan(begin,end,a,selfScore)
      }
    }
    ruleCounts
  }

  private def computeUnaryCounts(words: scala.Seq[W],
                                 inside: LogProbabilityParseChart[L],
                                 outside: LogProbabilityParseChart[L],
                                 validSpan: SpanScorer[L],
                                 totalProb: Double,
                                 spanVisitor: AnchoredSpanVisitor = AnchoredSpanVisitor.noOp) = {
    val ruleCounts = grammar.mkDenseVector()
    // TODO: only iterate over observed counts
    for{
      span <- 1 to words.length
      begin <- 0 to (words.length - span)
      end = begin + span
      a <- inside.top.enteredLabelIndexes(begin,end)
      r <- grammar.indexedUnaryRulesWithParent(a)
    } {
      val b = grammar.child(r)
      val bScore = inside.bot.labelScore(begin, end, b)
      val aScore = outside.top.labelScore(begin, end, a)
      val rScore = grammar.ruleScore(r) + validSpan.scoreUnaryRule(begin,end,r);
      val prob = exp(bScore + aScore + rScore - totalProb);
      if(prob != 0.0) {
        ruleCounts(r) += prob
        if(spanVisitor ne AnchoredSpanVisitor.noOp) spanVisitor.visitUnaryRule(begin,end,r,prob)
      }
    }
    ruleCounts
  }

  private val isTag = new collection.mutable.BitSet()
  lexicon.tags.foreach {l => isTag += grammar.labelIndex(l)}
}

object InsideOutside {

  final case class ExpectedCounts[W](ruleCounts: DenseVector[Double],
                                     wordCounts: SparseArrayMap[Counter[W,Double]], // parent -> word -> counts
                                     var logProb: Double) {

    def this(g: Grammar[_]) = this(g.mkDenseVector(),
      g.labelEncoder.fillSparseArrayMap(Counter[W,Double]()), 0.0)

    def decode[L](g: Grammar[L]) = (decodeRules(g,ruleCounts), decodeWords(g,wordCounts))

    def +=(c: ExpectedCounts[W]) = {
      val ExpectedCounts(bCounts,wCounts,tProb) = c

      this.ruleCounts += c.ruleCounts

      for( (k,vec) <- wCounts) {
        wordCounts.getOrElseUpdate(k) += vec
      }

      logProb += tProb
      this
    }

    def -=(c: ExpectedCounts[W]) = {
      val ExpectedCounts(bCounts,wCounts,tProb) = c

      this.ruleCounts -= c.ruleCounts

      for( (k,vec) <- wCounts) {
        wordCounts.getOrElseUpdate(k) -= vec
      }

      logProb -= tProb
      this
    }


  }

  def decodeRules[L](g: Grammar[L],
                     ruleCounts: Vector[Double]) = {
    val binaries = Counter2[L,BinaryRule[L],Double]()
    val unaries = Counter2[L,UnaryRule[L],Double]()

    for ( (r,score) <- ruleCounts.pairsIteratorNonZero) {
      val rule = g.index.get(r)
      rule match {
        case rule@BinaryRule(p,_,_) =>
          binaries(p,rule) = score
        case rule@UnaryRule(p,c) =>
          unaries(p,rule) = score
      }
    }
    (binaries,unaries)
  }

  def decodeWords[L,W](g: Grammar[L], wordCounts: SparseArrayMap[Counter[W,Double]]) = {
    val ctr = Counter2[L,W,Double]()
    for( (i,c) <- wordCounts) {
      ctr(g.labelIndex.get(i), ::) := c
    }
    ctr
  }



}