package org.clulab.reach

import org.clulab.coref.Coref
import org.clulab.reach.nxml.FriesEntry
import org.clulab.odin._
import org.clulab.reach.grounding._
import org.clulab.reach.mentions._
import RuleReader.{Rules, readResource}
import org.clulab.processors.Document
import org.clulab.processors.bionlp.BioNLPProcessor
import scala.collection.immutable.HashSet
import scala.collection.mutable
import org.clulab.reach.context._
import org.clulab.reach.context.ContextEngineFactory.Engine._

class ReachSystem(
    rules: Option[Rules] = None,
    proc: Option[BioNLPProcessor] = None,
    contextEngineType: Engine = Dummy,
    contextParams: Map[String, String] = Map()
) {

  import ReachSystem._

  val entityRules = if (rules.isEmpty) readResource(RuleReader.entitiesMasterFile) else rules.get.entities
  val modificationRules = if (rules.isEmpty) readResource(RuleReader.modificationsMasterFile) else rules.get.modifications
  val eventRules = if (rules.isEmpty) readResource(RuleReader.eventsMasterFile) else rules.get.events
  val contextRules = if (rules.isEmpty) readResource(RuleReader.contextRelationsFile) else rules.get.context
  // initialize actions object
  val actions = new DarpaActions
  val entityLookup = new ReachEntityLookup // initialize entity lookup (find grounding candidates)
  val grounder = new ReachGrounder
  // start entity extraction engine
  // this engine extracts all physical entities of interest
  val entityEngine = ExtractorEngine(entityRules, actions)
  // start modification engine
  // this engine extracts modification features and attaches them to the corresponding entity
  val modificationEngine = ExtractorEngine(modificationRules, actions)
  // start event extraction engine
  // this engine extracts simple and recursive events and applies coreference
  val eventEngine = ExtractorEngine(eventRules, actions, actions.cleanupEvents)
  // initialize processor
  val processor = if (proc.isEmpty) new BioNLPProcessor else proc.get
  processor.annotate("something")

  /** returns string with all rules used by the system */
  def allRules: String =
    Seq(entityRules, modificationRules, eventRules, contextRules).mkString("\n\n")

  def mkDoc(text: String, docId: String, chunkId: String = ""): Document = {
    val doc = processor.annotate(text, keepText = true)
    val id = if (chunkId.isEmpty) docId else s"${docId}_${chunkId}"
    doc.id = Some(id)
    doc
  }

  def extractFrom(entries: Seq[FriesEntry]): Seq[BioMention] =
    extractFrom(entries, entries.map{
        e => mkDoc(e.text, e.name, e.chunkId)
    })

  def extractFrom(entries: Seq[FriesEntry], documents: Seq[Document]): Seq[BioMention] = {
    // initialize the context engine
    val contextEngine = ContextEngineFactory.buildEngine(contextEngineType, contextParams)

    val entitiesPerEntry = for (doc <- documents) yield extractEntitiesFrom(doc)
    contextEngine.infer(entries, documents, entitiesPerEntry)
    val entitiesWithContextPerEntry = for (es <- entitiesPerEntry) yield contextEngine.assign(es)
    val eventsPerEntry = for ((doc, es) <- documents zip entitiesWithContextPerEntry) yield {
        val events = extractEventsFrom(doc, es)
        MentionFilter.keepMostCompleteMentions(events, State(events))
    }
    contextEngine.update(eventsPerEntry.flatten)
    val eventsWithContext = contextEngine.assign(eventsPerEntry.flatten)
    val grounded = grounder(eventsWithContext)
    // Coref expects to get all mentions grouped by document
    val resolved = resolveCoref(groupMentionsByDocument(grounded, documents))
    // Coref introduced incomplete Mentions that now need to be pruned
    val complete = MentionFilter.keepMostCompleteMentions(resolved, State(resolved)).map(_.toCorefMention)
    // val complete = MentionFilter.keepMostCompleteMentions(eventsWithContext, State(eventsWithContext)).map(_.toBioMention)

    resolveDisplay(complete)
  }

  // this method groups the mentions by document
  // the sequence of documents should be sorted in order of appearance in the paper
  def groupMentionsByDocument(mentions: Seq[BioMention], documents: Seq[Document]): Seq[Seq[BioMention]] = {
    for (doc <- documents) yield mentions.filter(_.document == doc)
  }

  // the extractFrom() methods are the main entry points to the reach system
  def extractFrom(entry: FriesEntry): Seq[BioMention] =
    extractFrom(entry.text, entry.name, entry.chunkId)

  def extractFrom(text: String, docId: String, chunkId: String): Seq[BioMention] = {
    extractFrom(mkDoc(text, docId, chunkId))
  }

  def extractFrom(doc: Document): Seq[BioMention] = {
    require(doc.id.isDefined, "document must have an id")
    require(doc.text.isDefined, "document should keep original text")
    extractFrom(Seq(FriesEntry(doc.id.get, "NoChunk", "NoSection", "NoSection", false, doc.text.get)), Seq(doc))
  }

  def extractEntitiesFrom(doc: Document): Seq[BioMention] = {
    // extract entities
    val entities = entityEngine.extractByType[BioMention](doc)
    // attach modification features to entities
    val modifiedEntities = modificationEngine.extractByType[BioMention](doc, State(entities))
    val mutationAddedEntities = modifiedEntities flatMap {
      case m: BioTextBoundMention => mutationsToMentions(m)
      case m => Seq(m)
    }
    // add grounding candidates to entities
    entityLookup(mutationAddedEntities)
  }

  /** If the given mention has many mutations attached to it, return a mention for each mutation. */
  def mutationsToMentions(mention: BioTextBoundMention): Seq[BioMention] = {
    val mutations = mention.modifications.filter(_.isInstanceOf[Mutant])
    if (mutations.isEmpty || mutations.size == 1)
      Seq(mention)
    else {
      mutations.map { mut =>
        val tbm = new BioTextBoundMention(mention.labels, mention.tokenInterval,
                                          mention.sentence, mention.document,
                                          mention.keep, mention.foundBy)
        // copy all attachments
        BioMention.copyAttachments(mention, tbm)
        // remove all mutations
        tbm.modifications = tbm.modifications diff mutations
        // add desired mutation only
        tbm.modifications += mut
        tbm
      }.toSeq
    }
  }

  def extractEventsFrom(doc: Document, entities: Seq[BioMention]): Seq[BioMention] = {
    val mentions = eventEngine.extractByType[BioMention](doc, State(entities))
    // clean modified entities
    // remove ModificationTriggers
    // Make sure we don't have any "ModificationTrigger" Mentions
    val validMentions = mentions.filterNot(_ matches "ModificationTrigger")
    // handle multiple Negation modifications
    NegationHandler.handleNegations(validMentions)
    validMentions
  }

  // this method gets sequence composed of sequences of mentions, one per doc.
  // each doc corresponds to a chunk of the paper, and it expects them to be in order of appearance
  def resolveCoref(eventsPerDocument: Seq[Seq[BioMention]]): Seq[CorefMention] = {
    val coref = new Coref()
    coref(eventsPerDocument).flatten
  }

}

object ReachSystem {

  /** This function should set the right displayMention for each mention.
    * NB: By default the displayMention is set to the main label of the mention,
    *     so, after extraction, it should never be null.
    */
  def resolveDisplay (ms: Seq[CorefMention]): Seq[CorefMention] = {
    for (m <- ms) {
      m match {
        case em:TextBoundMention with Display with Grounding =>
          resolveDisplayForEntity(m)
        case rm:RelationMention with Display with Grounding =>
          resolveDisplayForArguments(m, new HashSet[String])
        case vm:EventMention with Display with Grounding =>
          resolveDisplayForArguments(m, new HashSet[String])
        case _ =>                           // nothing to do
      }
    }
    ms
  }

  /** Recursively traverse the arguments of events and handle GPP entities. */
  def resolveDisplayForArguments (em: CorefMention, parents: Set[String]) {
    if (em.labels.contains("Event")) {      // recursively traverse the arguments of events
      val newParents = new mutable.HashSet[String]()
      newParents ++= parents
      newParents += em.label
      em.arguments.values.foreach(ms => ms.foreach( m => {
        val crm = m.asInstanceOf[CorefMention]
        resolveDisplayForArguments(crm.antecedentOrElse(crm), newParents.toSet)
      }))
    }
    else if (em.labels.contains("Gene_or_gene_product")) { // we only need to disambiguate these
      resolveDisplayForEntity(em, Some(parents))
    }
  }

  /** Set the displayLabel for a single mention, using optional parent label set information. */
  def resolveDisplayForEntity (em: CorefMention, parents: Option[Set[String]] = None) {
    if (em.labels.contains("Gene_or_gene_product")) {
      if (em.isGrounded && ReachKBUtils.isFamilyGrounded(em)) {
        em.displayLabel = "Family"
      }
      else if (parents.exists(_.contains("Transcription"))) {
        em.displayLabel = "Gene"
      } else {
        em.displayLabel = "Protein"
      }
    }
  }

}