package edu.arizona.sista.bionlp

import edu.arizona.sista.processors.Document
import edu.arizona.sista.struct.Interval
import edu.arizona.sista.odin._
import edu.arizona.sista.bionlp.mentions._

class DarpaActions extends Actions {

  def splitSimpleEvents(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case m: EventMention if m matches "SimpleEvent" =>
      // Do we have a regulation?
      if (m.arguments.keySet contains "cause") {
        // FIXME There could be more than one cause...
        val cause:Seq[Mention] = m.arguments("cause")
        val evArgs = m.arguments - "cause"
        val ev = new BioEventMention(
          m.labels, m.trigger, evArgs, m.sentence, m.document, m.keep, m.foundBy)
        // make sure the regulation is valid
        val controlledArgs:Set[Mention] = evArgs.values.flatten.toSet
          cause match {
          // controller of an event should not be an arg in the controlled
          case reg if cause.forall(c => !controlledArgs.contains(c)) => {
            val regArgs = Map("controlled" -> Seq(ev), "controller" -> cause)
            val reg = new BioRelationMention(
              Seq("Positive_regulation", "ComplexEvent", "Event"),
              regArgs, m.sentence, m.document, m.keep, m.foundBy)
            Seq(reg, ev)
          }
          case _ => Nil
        }
      } else Seq(m.toBioMention)
    case m => Seq(m.toBioMention)
  }

  // FIXME this is an ugly hack that has to go
  override val identity: Action = splitSimpleEvents

  /** This action handles the creation of mentions from labels generated by the NER system.
    * Rules that use this action should run in an iteration following and rules recognizing
    * "custom" entities. This action will only create mentions if no other mentions overlap
    * with a NER label sequence.
    */
  def mkNERMentions(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions flatMap { m =>
      val candidates = state.mentionsFor(m.sentence, m.tokenInterval.toSeq)
      // do any candidates intersect the mention?
      val overlap = candidates.exists(_.tokenInterval.overlaps(m.tokenInterval))
      if (overlap) None else Some(m.toBioMention)
    }
  }

  /** This action handles the creation of ubiquitination EventMentions.
    * A Ubiquitination event cannot involve arguments (theme/cause) with the text Ubiquitin.
    */
  def mkUbiquitination(mentions: Seq[Mention], state: State): Seq[Mention] = {
    val filteredMentions = mentions.filter { m =>
      // Don't allow Ubiquitin
      !m.arguments.values.flatten.exists(_.text.toLowerCase.startsWith("ubiq")) 
    }
    val bioMentions = filteredMentions.map(_.toBioMention)
    // TODO: a temporary hack to convert theme+cause ubiqs => regs
    splitSimpleEvents(bioMentions, state)
  }

  /** This action handles the creation of Binding EventMentions for rules using token patterns.
    * Currently Odin does not support the use of arguments of the same name in Token patterns.
    * Because of this, we have adopted the convention of following duplicate names with a
    * unique number (ex. theme1, theme2).
    * mkBinding simply unifies named arguments of this type (ex. theme1 & theme2 -> theme)
    */
  def mkBinding(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case m: EventMention =>
      val arguments = m.arguments
      val themes = for {
        name <- arguments.keys.toSeq
        if name startsWith "theme"
        theme <- arguments(name)
      } yield theme
      // remove bindings with less than two themes
      if (themes.size < 2) Nil
      // if binding has two themes we are done
      else if (themes.size == 2) {
        val args = Map("theme" -> themes)
        Seq(new BioEventMention(
          m.labels, m.trigger, args, m.sentence, m.document, m.keep, m.foundBy))
      } else {
        // binarize bindings
        // return bindings with pairs of themes
        for (pair <- themes.combinations(2)) yield {
          val args = Map("theme" -> pair)
          new BioEventMention(m.labels, m.trigger, args, m.sentence, m.document, m.keep, m.foundBy)
        }
      }
  }

  /**
   * This action decomposes RelationMentions with the label Modification to the matched TB entity with the appropriate Modification
   * @return Nil (Modifications are added in-place)
   */
  def mkModification(mentions: Seq[Mention], state: State): Seq[Mention] = {
    // retrieve the appropriate modification label
    def getModification(text: String): String = text.toLowerCase match {
      case acet if acet contains "acetylat" => "acetylated"
      case farne if farne contains "farnesylat" => "farnesylated"
      case glyco if glyco contains "glycosylat" =>"glycosylated"
      case hydrox if hydrox contains "hydroxylat" =>"hydroxylated"
      case meth if meth contains "methylat" => "methylated"
      case phos if phos contains "phosphorylat" => "phosphorylated"
      case ribo if ribo contains "ribosylat" => "ribosylated"
      case sumo if sumo contains "sumoylat" =>"sumoylated"
      case ubiq if ubiq contains "ubiquitinat" => "ubiquitinated"
      case _ => "UNKNOWN"
    }

    mentions flatMap {
      case ptm: RelationMention if ptm.label == "PTM" => {
        //println("found a modification...")
        val trigger = ptm.arguments("mod").head
        // If this creates a new mention, we have a bug because it won't end up in the State
        val bioMention = ptm.arguments("entity").head.toBioMention
        val site = if (ptm.arguments.keySet.contains("site")) Some(ptm.arguments("site").head) else None
        // This is the TextBoundMention for the ModifcationTrigger
        val evidence = ptm.arguments("mod").head
        val label = getModification(evidence.text)
        // If we have a label, add the modification in-place
        if (label != "UNKNOWN") bioMention.modifications += PTM(label, Some(evidence), site)
        Nil // don't return anything; this mention is already in the State
      }
    }
  }

  /**
   * Sometimes it's easiest to find the site associated with a BioChemicalEntity before event detection
   * @return Nil (Modifications are added in-place)
   */
  def storeEventSite(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions foreach { m =>
      val bioMention = m.arguments("entity").head.toBioMention
      val eSite = m.arguments("site").head
      bioMention.modifications += EventSite(site = eSite)
    }
    Nil
  }
}
