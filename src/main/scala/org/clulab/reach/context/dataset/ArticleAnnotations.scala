package org.clulab.reach.context.dataset

import java.io.File
import io.Source
import ai.lum.common.Interval
import ai.lum.nxmlreader.standoff.Tree

case class ContextType(val contextType:String, val id:String)

object ContextType{
  def parse(annotationId:String) = {
    val tokens = annotationId.split(":", 2)
    val (namespace, gid) = (tokens(0), tokens(1))

    namespace match {
      case "taxonomy" => this("Species", annotationId)
      case "cellosaurus" => this("CellLine", annotationId)
      case "cellontology" => this("CellType", annotationId)
      case "uberon" => this("Organ", annotationId)
      case "tissuelist" => this("Organ", annotationId)
      case "uaz" =>
       val x = gid.split("-")(1)
       x match {
         case "org" => this("Organ", annotationId)
         case "cline" => this("CellLine", annotationId)
         case "ct" => this("CellType", annotationId)
       }
    }
  }
}

case class EventAnnotation(val sentenceId:Int, val interval:Interval, val contextsOf:Seq[ContextType])
case class ContextAnnotation(val sentenceId: Int, val interval:Interval, val contextType:ContextType)

case class ArticleAnnotations(val sentences:Map[Int, String],
   val eventAnnotations:Seq[EventAnnotation],
   val contextAnnotations:Seq[ContextAnnotation],
   val standoff:Option[Tree] = None)

object ArticleAnnotations{
  def readPaperAnnotations(directory:String):ArticleAnnotations = {
    // Read the tsv annotations from a paper
    val rawSentences = Source.fromFile(new File(directory, "sentences.tsv")).getLines
    val sentences:Map[Int, String] = rawSentences.map{
      s =>
        val tokens = s.split("\t")
        (tokens(0).toInt, tokens(1))
    }.toMap

    val rawEvents = Source.fromFile(new File(directory, "events.tsv")).getLines
    val events = rawEvents.map{
      s =>
        val tokens = s.split("\t")
        val sentenceId = tokens(0).toInt

        val bounds = tokens(1).split("-").map(_.toInt)
        val (start, end) = (bounds(0), bounds(1))
        val interval = if(start == end) Interval.singleton(start) else Interval.closed(start, end)
        val contexts = tokens(2).split(",").map(ContextType.parse(_))

        EventAnnotation(sentenceId, interval, contexts)
    }.toSeq

    val rawContext = Source.fromFile(new File(directory, "context.tsv")).getLines
    val context = rawContext.map{
      s =>
        val tokens = s.split("\t")

        val sentenceId = tokens(0).toInt

        val bounds = tokens(1).split("-").map(_.toInt)
        val (start, end) = (bounds(0), bounds(1))
        val interval = if(start == end) Interval.singleton(start) else Interval.closed(start, end)
        val context = ContextType.parse(tokens(2))

        ContextAnnotation(sentenceId, interval, context)
    }.toSeq

    val soffFile = new File(directory, "standoff.json")
    val standoff = if(soffFile.exists) Some(Tree.readJson(soffFile.getPath)) else None

    ArticleAnnotations(sentences, events, context, standoff)
  }
}
