package scaps.searchEngine.index

import java.io.Reader
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.util.Try
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents
import org.apache.lucene.analysis.core.KeywordAnalyzer
import org.apache.lucene.analysis.core.LowerCaseFilter
import org.apache.lucene.analysis.core.WhitespaceAnalyzer
import org.apache.lucene.analysis.core.WhitespaceTokenizer
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper
import org.apache.lucene.analysis.miscellaneous.WordDelimiterFilter
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field.Store
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.FieldInvertState
import org.apache.lucene.index.Term
import org.apache.lucene.queries.CustomScoreQuery
import org.apache.lucene.queries.function.FunctionQuery
import org.apache.lucene.queries.function.FunctionValues
import org.apache.lucene.queries.function.valuesource.DualFloatFunction
import org.apache.lucene.queries.function.valuesource.IntFieldSource
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.similarities.DefaultSimilarity
import org.apache.lucene.search.similarities.PerFieldSimilarityWrapper
import org.apache.lucene.search.similarities.TFIDFSimilarity
import org.apache.lucene.store.Directory
import org.apache.lucene.util.BytesRef
import scalaz.{ \/ => \/ }
import scalaz.syntax.either.ToEitherOps
import scaps.searchEngine.ApiQuery
import scaps.searchEngine.ProcessingError
import scaps.searchEngine.TooUnspecific
import scaps.settings.Settings
import scaps.webapi.Module
import scaps.webapi.TermEntity
import scaps.utils.Statistic
import org.apache.lucene.queries.function.valuesource.SimpleFloatFunction
import org.apache.lucene.util.QueryBuilder
import org.apache.lucene.analysis.util.CharTokenizer

class TermsIndex(val dir: Directory, settings: Settings) extends Index[TermEntity] {
  import TermsIndex._

  private val nameAnalyzer = new Analyzer {
    override def createComponents(fieldName: String, reader: Reader) = {
      val tokenizer = new IdentifierAndWhitespaceDelimiterTokenizer(reader)
      val ts1 = new WordDelimiterFilter(
        tokenizer,
        WordDelimiterFilter.GENERATE_WORD_PARTS |
          WordDelimiterFilter.GENERATE_NUMBER_PARTS |
          WordDelimiterFilter.PRESERVE_ORIGINAL |
          WordDelimiterFilter.SPLIT_ON_CASE_CHANGE |
          WordDelimiterFilter.SPLIT_ON_NUMERICS,
        null)
      val ts2 = new LowerCaseFilter(ts1)
      new TokenStreamComponents(tokenizer, ts2)
    }
  }

  private val nameQueryBuilder = new QueryBuilder(nameAnalyzer)

  private val docAnalyzer = nameAnalyzer

  private val docQueryBuilder = new QueryBuilder(docAnalyzer)

  override val analyzer =
    new PerFieldAnalyzerWrapper(new StandardAnalyzer(), Map(
      fields.fingerprint -> new WhitespaceAnalyzer,
      fields.moduleId -> new KeywordAnalyzer,
      fields.name -> nameAnalyzer,
      fields.doc -> docAnalyzer).asJava)

  override val similarity = new PerFieldSimilarityWrapper {
    val default = new DefaultSimilarity

    override def get(field: String) = field match {
      case fields.fingerprint => new FingerprintSimilarity(settings)
      case fields.moduleId    => new ModuleIdSimilarity()
      case _                  => default
    }
  }

  def addEntities(entities: Seq[TermEntity]): Try[Unit] =
    withWriter { writer =>
      val docs = entities.map(toDocument)
      writer.addDocuments(docs.asJava)
    }

  def find(query: ApiQuery, moduleIds: Set[String]): Try[ProcessingError \/ Seq[TermEntity]] =
    Try {
      //      toLuceneQuery(query, moduleIds).map(
      //        lq => search(lq, 6, Some((term, expl) => println(s"${term.withoutComment}\n${term.typeFingerprint}\n$expl"))).get)
      toLuceneQuery(query, moduleIds).map(
        lq => search(lq, settings.query.maxResults).get)
    }

  def deleteEntitiesIn(module: Module): Try[Unit] =
    withWriter { writer =>
      writer.deleteDocuments(new Term(fields.moduleId, module.moduleId))
    }

  private def toLuceneQuery(query: ApiQuery, moduleIds: Set[String]): ProcessingError \/ Query = {
    try {
      val keys = new BooleanQuery

      Option(nameQueryBuilder.createBooleanQuery(fields.name, query.keywords))
        .foreach { nameQuery =>
          nameQuery.setBoost(settings.query.nameBoost.toFloat)
          keys.add(nameQuery, Occur.SHOULD)
        }

      Option(docQueryBuilder.createBooleanQuery(fields.doc, query.keywords))
        .foreach { docQuery =>
          docQuery.setBoost(settings.query.docBoost.toFloat)
          keys.add(docQuery, Occur.SHOULD)
        }

      val docLenBoost = new FunctionQuery(
        new SimpleFloatFunction(new IntFieldSource(fields.fingerprintLength)) {
          val lengthWeight = settings.query.lengthNormWeight / math.sqrt(query.queryFingerprintLength)

          def func(doc: Int, fingerprintLengthValues: FunctionValues): Float = {
            val fingerprintLength = fingerprintLengthValues.intVal(doc)
            (1d /
              (math.pow(
                lengthWeight * math.abs(query.queryFingerprintLength - fingerprintLength),
                2) + 1)).toFloat
          }

          def name(): String = "docLenNormalization"
        })

      val keysAndTypes = new TypeFingerprintQuery(
        fields.fingerprint, query.tpe, keys, settings.query.fingerprintFrequencyCutoff, docLenBoost)

      val modules = new BooleanQuery

      if (!moduleIds.isEmpty) {
        moduleIds.foreach { moduleId =>
          val tq = new TermQuery(new Term(fields.moduleId, moduleId))
          modules.add(tq, Occur.SHOULD)
        }
      } else {
        modules.add(new MatchAllDocsQuery, Occur.SHOULD)
      }
      modules.setBoost(0)

      val q = new BooleanQuery
      q.add(keysAndTypes, Occur.MUST)
      q.add(modules, Occur.MUST)

      q.right
    } catch {
      case _: BooleanQuery.TooManyClauses => TooUnspecific().left
    }
  }

  def allTerms(): Try[Seq[TermEntity]] =
    search(new MatchAllDocsQuery)

  private[index] def findTermsByName(name: String): Try[Seq[TermEntity]] =
    search(nameQueryBuilder.createBooleanQuery(fields.name, name))

  override def toDocument(entity: TermEntity): Document = {
    val doc = new Document

    def add(field: String, value: String) =
      doc.add(new TextField(field, value, Store.NO))

    add(fields.name, entity.name)
    add(fields.moduleId, entity.module.moduleId)
    entity.typeFingerprint.foreach { fp =>
      doc.add(new TextField(fields.fingerprint, fp, Store.YES))
    }
    add(fields.doc, entity.comment)
    entity.flags.foreach { flag =>
      add(fields.flags, flag.name)
    }
    doc.add(new StoredField(fields.entity, upickle.write(entity)))

    doc.add(new NumericDocValuesField(fields.fingerprintLength, entity.tpe.toList.length))

    doc
  }

  override def toEntity(doc: Document): TermEntity = {
    val json = doc.getValues(fields.entity)(0)

    upickle.read[TermEntity](json)
  }
}

object TermsIndex {
  object fields {
    val name = "name"
    val fingerprint = "fingerprint"
    val doc = "doc"
    val entity = "entity"
    val moduleId = "moduleId"
    val flags = "flags"
    val fingerprintLength = "fingerprintLength"
  }

  class FingerprintSimilarity(settings: Settings) extends DefaultSimilarity {
    // We use type frequencies instead of document term frequency to boost uncommon
    // types in queries
    override def idf(docFreq: Long, numDocs: Long) = 1f

    // Use the `fingerprintLength` field to normalize fingerprint length
    override def lengthNorm(state: FieldInvertState): Float = 1f
  }

  class ModuleIdSimilarity extends DefaultSimilarity {
    override def idf(docFreq: Long, numDocs: Long) = 1f
  }

  class IdentifierAndWhitespaceDelimiterTokenizer(reader: Reader) extends CharTokenizer(reader) {
    override def isTokenChar(c: Int): Boolean = {
      !(Character.isWhitespace(c) || c == '.' || c == '#')
    }
  }
}
