package reactivemongo.api.commands.bson

import reactivemongo.api.commands._
import reactivemongo.bson._

object BSONDropDatabaseImplicits {
  implicit object DropDatabaseWriter extends BSONDocumentWriter[DropDatabase.type] {
    def write(dd: DropDatabase.type): BSONDocument =
      BSONDocument("dropDatabase" -> 1)
  }
}

object BSONListCollectionNamesImplicits {
  implicit object ListCollectionNamesWriter extends BSONDocumentWriter[ListCollectionNames.type] {
    def write(ls: ListCollectionNames.type): BSONDocument =
      BSONDocument("listCollections" -> 1)
  }

  implicit object BSONCollectionNameReaders extends BSONDocumentReader[CollectionNames] {
    def read(doc: BSONDocument): CollectionNames = (for {
      _  <- doc.getAs[BSONNumberLike]("ok").map(_.toInt).filter(_ == 1)
      cr <- doc.getAs[BSONDocument]("cursor")
      fb <- cr.getAs[List[BSONDocument]]("firstBatch")
      ns <- wtColNames(fb, Nil)
    } yield CollectionNames(ns)).getOrElse[CollectionNames](throw new Exception(
      "Fails to read collection names"))
  }

  @annotation.tailrec
  private def wtColNames(meta: List[BSONDocument], ns: List[String]): Option[List[String]] = meta match {
    case d :: ds => d.getAs[String]("name") match {
      case Some(n) => wtColNames(ds, n :: ns)
      case _       => None // error
    }
    case _       => Some(ns.reverse)
  }  
}

object BSONDropImplicits {
  implicit object DropWriter extends BSONDocumentWriter[ResolvedCollectionCommand[Drop.type]] {
    def write(command: ResolvedCollectionCommand[Drop.type]): BSONDocument =
      BSONDocument("drop" -> command.collection)
  }
}

object BSONEmptyCappedImplicits {
  implicit object EmptyCappedWriter extends BSONDocumentWriter[ResolvedCollectionCommand[EmptyCapped.type]] {
    def write(command: ResolvedCollectionCommand[EmptyCapped.type]): BSONDocument =
      BSONDocument("emptyCapped" -> command.collection)
  }
}

object BSONRenameCollectionImplicits {
  implicit object RenameCollectionWriter extends BSONDocumentWriter[RenameCollection] {
    def write(command: RenameCollection): BSONDocument =
      BSONDocument(
        "renameCollection" -> command.fullyQualifiedCollectionName,
        "to" -> command.fullyQualifiedTargetName,
        "dropTarget" -> command.dropTarget)
  }
}

object BSONCreateImplicits {
  implicit object CappedWriter extends BSONDocumentWriter[Capped] {
    def write(capped: Capped): BSONDocument =
      BSONDocument(
        "size" -> capped.size,
        "max" -> capped.max)
  }
  implicit object CreateWriter extends BSONDocumentWriter[ResolvedCollectionCommand[Create]] {
    def write(command: ResolvedCollectionCommand[Create]): BSONDocument =
      BSONDocument(
      "create" -> command.collection,
      "autoIndexId" -> command.command.autoIndexId) ++ command.command.capped.map( capped =>
        CappedWriter.write(capped) ++ ("capped" -> true)).getOrElse(BSONDocument())
  }
}

object BSONCollStatsImplicits {
  implicit object CollStatsWriter extends BSONDocumentWriter[ResolvedCollectionCommand[CollStats]] {
    def write(command: ResolvedCollectionCommand[CollStats]): BSONDocument =
      BSONDocument(
        "collStats" -> command.collection, "scale" -> command.command.scale)
  }

  implicit object CollStatsResultReader extends DealingWithGenericCommandErrorsReader[CollStatsResult] {
    def readResult(doc: BSONDocument): CollStatsResult = CollStatsResult(
      doc.getAs[String]("ns").get,
      doc.getAs[BSONNumberLike]("count").map(_.toInt).get,
      doc.getAs[BSONNumberLike]("size").map(_.toDouble).get,
      doc.getAs[BSONNumberLike]("avgObjSize").map(_.toDouble),
      doc.getAs[BSONNumberLike]("storageSize").map(_.toDouble).get,
      doc.getAs[BSONNumberLike]("numExtents").map(_.toInt),
      doc.getAs[BSONNumberLike]("nindexes").map(_.toInt).get,
      doc.getAs[BSONNumberLike]("lastExtentSize").map(_.toInt),
      doc.getAs[BSONNumberLike]("paddingFactor").map(_.toDouble),
      doc.getAs[BSONNumberLike]("systemFlags").map(_.toInt),
      doc.getAs[BSONNumberLike]("userFlags").map(_.toInt),
      doc.getAs[BSONNumberLike]("totalIndexSize").map(_.toInt).get,
      {
        val indexSizes = doc.getAs[BSONDocument]("indexSizes").get
          (for (kv <- indexSizes.elements) yield kv._1 -> kv._2.asInstanceOf[BSONInteger].value).toArray
      },
      doc.getAs[BSONBooleanLike]("capped").fold(false)(_.toBoolean),
      doc.getAs[BSONNumberLike]("max").map(_.toLong))
  }
}

object BSONConvertToCappedImplicits {
  implicit object ConvertToCappedWriter extends BSONDocumentWriter[ResolvedCollectionCommand[ConvertToCapped]] {
    def write(command: ResolvedCollectionCommand[ConvertToCapped]): BSONDocument =
      BSONDocument("convertToCapped" -> command.collection) ++ BSONCreateImplicits.CappedWriter.write(command.command.capped)
  }
}

object BSONDropIndexesImplicits {
  implicit object BSONDropIndexesWriter extends BSONDocumentWriter[ResolvedCollectionCommand[DropIndexes]] {
    def write(command: ResolvedCollectionCommand[DropIndexes]): BSONDocument =
      BSONDocument(
        "dropIndexes" -> command.collection,
        "index" -> command.command.index
      )
  }

  implicit object BSONDropIndexesReader extends DealingWithGenericCommandErrorsReader[DropIndexesResult] {
    def readResult(doc: BSONDocument): DropIndexesResult =
      DropIndexesResult(doc.getAs[BSONNumberLike]("nIndexesWas").map(_.toInt).getOrElse(0))
  }
}

object BSONListIndexesImplicits {
  import scala.util.{ Failure, Success, Try }
  import reactivemongo.api.indexes.{ Index, IndexesManager }
  
  implicit object BSONListIndexesWriter extends BSONDocumentWriter[ResolvedCollectionCommand[ListIndexes]] {
    def write(command: ResolvedCollectionCommand[ListIndexes]): BSONDocument = 
      BSONDocument("listIndexes" -> command.collection)
  }

  implicit object BSONIndexListReader extends BSONDocumentReader[List[Index]] {
    @annotation.tailrec
    def readBatch(batch: List[BSONDocument], indexes: List[Index]): Try[List[Index]] = batch match {
      case d :: ds => d.asTry[Index](IndexesManager.IndexReader) match {
        case Success(i) => readBatch(ds, i :: indexes)
        case Failure(e) => Failure(e)
      }
      case _ => Success(indexes)
    }

    def read(doc: BSONDocument): List[Index] = (for {
      _ <- doc.getAs[BSONNumberLike]("ok").fold[Option[Unit]](
        throw new Exception("the result of listIndexes must be ok")) { ok =>
        if (ok.toInt == 1) Some(())
        else throw new Exception(doc.getAs[String]("errmsg").fold(
          "the result of listIndexes must be ok")(
          e => s"fails to create index: $e"))
      }
      a <- doc.getAs[BSONDocument]("cursor")
      b <- a.getAs[List[BSONDocument]]("firstBatch")
    } yield b).fold[List[Index]](throw new Exception(
      "the cursor and firstBatch must be defined"))(readBatch(_, Nil).get)
  }  
}

object BSONCreateIndexesImplicits {
  import reactivemongo.api.commands.WriteResult

  implicit object BSONCreateIndexesWriter extends BSONDocumentWriter[ResolvedCollectionCommand[CreateIndexes]] {
    import reactivemongo.api.indexes.{ IndexesManager, NSIndex }
    implicit val nsIndexWriter = IndexesManager.NSIndexWriter

    def write(cmd: ResolvedCollectionCommand[CreateIndexes]): BSONDocument = {
      BSONDocument("createIndexes" -> cmd.collection,
        "indexes" -> cmd.command.indexes.map(NSIndex(
          cmd.command.db + "." + cmd.collection, _)))
    }
  }

  implicit object BSONCreateIndexesResultReader
      extends BSONDocumentReader[WriteResult] {

    import reactivemongo.api.commands.DefaultWriteResult

    def read(doc: BSONDocument): WriteResult =
      doc.getAs[BSONNumberLike]("ok").map(_.toInt).fold[WriteResult](
        throw new Exception("the count must be defined")) { n =>
        doc.getAs[String]("errmsg").fold[WriteResult](
          DefaultWriteResult(true, n, Nil, None, None, None))(
          err => DefaultWriteResult(false, n, Nil, None, None, Some(err)))
      }
  }
}
