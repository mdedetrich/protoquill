package io.getquill.context.jdbc

import java.sql._


import io.getquill.ReturnAction._
import io.getquill.context.sql.idiom.SqlIdiom
import io.getquill.context.{ Context, ContextEffect }
import io.getquill.util.ContextLogger
import io.getquill.context.ExecutionInfo
import io.getquill.generic.EncodingDsl
import io.getquill.context.sql.SqlContext
import io.getquill.NamingStrategy
import io.getquill.ReturnAction
import io.getquill.context.StagedPrepare
import io.getquill.context.PrepareContext

trait JdbcContextBase[Dialect <: SqlIdiom, Naming <: NamingStrategy]
extends JdbcContextSimplified[Dialect, Naming]
with StagedPrepare[Dialect, Naming] {
  def constructPrepareQuery(f: Connection => Result[PreparedStatement]): Connection => Result[PreparedStatement] = f
  def constructPrepareAction(f: Connection => Result[PreparedStatement]): Connection => Result[PreparedStatement] = f
  def constructPrepareBatchAction(f: Connection => Result[List[PreparedStatement]]): Connection => Result[List[PreparedStatement]] = f
}

trait JdbcContextSimplified[Dialect <: SqlIdiom, Naming <: NamingStrategy]
  extends JdbcRunContext[Dialect, Naming]
  with PrepareContext[Dialect, Naming] {

  override type PrepareQueryResult = Connection => Result[PreparedStatement]
  override type PrepareActionResult = Connection => Result[PreparedStatement]
  override type PrepareBatchActionResult = Connection => Result[List[PreparedStatement]]

  import effect._
  def constructPrepareQuery(f: Connection => Result[PreparedStatement]): PrepareQueryResult
  def constructPrepareAction(f: Connection => Result[PreparedStatement]): PrepareActionResult
  def constructPrepareBatchAction(f: Connection => Result[List[PreparedStatement]]): PrepareBatchActionResult

  def prepareQuery(sql: String, prepare: Prepare = identityPrepare)(executionInfo: ExecutionInfo, dc: DatasourceContext): PrepareQueryResult =
    constructPrepareQuery(prepareSingle(sql, prepare)(executionInfo, dc))

  def prepareAction(sql: String, prepare: Prepare = identityPrepare)(executionInfo: ExecutionInfo, dc: DatasourceContext): PrepareActionResult =
    constructPrepareAction(prepareSingle(sql, prepare)(executionInfo, dc))

  def prepareSingle(sql: String, prepare: Prepare = identityPrepare)(executionInfo: ExecutionInfo, dc: DatasourceContext): Connection => Result[PreparedStatement] =
    (conn: Connection) => wrap {
      val (params, ps) = prepare(conn.prepareStatement(sql), conn)
      logger.logQuery(sql, params)
      ps
    }

  def prepareBatchAction(groups: List[BatchGroup])(executionInfo: ExecutionInfo, dc: DatasourceContext): PrepareBatchActionResult =
    constructPrepareBatchAction {
      (session: Connection) =>
        seq {
          val batches = groups.flatMap {
            case BatchGroup(sql, prepares) =>
              prepares.map(sql -> _)
          }
          batches.map {
            case (sql, prepare) =>
              val prepareSql = prepareSingle(sql, prepare)(executionInfo, dc)
              prepareSql(session)
          }
        }
    }
}

trait JdbcRunContext[Dialect <: SqlIdiom, Naming <: NamingStrategy]
  extends SqlContext[Dialect, Naming]
  with Encoders
  with Decoders
{

  // Dotty doesn't like that this is defined in both Encoders and Decoders.
  // Makes us define it here in order to resolve the conflict.
  type Index = Int

  private[getquill] val logger = ContextLogger(classOf[JdbcRunContext[_, _]]) // Note this is incorrect in the Scala 2 JdbcRunContext.scala

  // Not required for JdbcRunContext in Scala2-Quill but it's a typing error. It only works
  // because executeQuery is not actually defined in Context.scala therefore typing doesn't have
  // to be correct on the base-level. Same issue with RunActionResult and others
  // override type Result[T] = T
  override type RunQueryResult[T] = List[T]
  override type RunQuerySingleResult[T] = T
  override type RunActionResult = Long
  override type RunActionReturningResult[T] = T
  override type RunBatchActionResult = List[Long]
  override type RunBatchActionReturningResult[T] = List[T]


  override type PrepareRow = PreparedStatement
  override type ResultRow = ResultSet
  override type Session = Connection

  override type DatasourceContext = Unit
  override def context: DatasourceContext = ()

  protected val effect: ContextEffect[Result]
  import effect._

  protected def withConnection[T](f: Connection => Result[T]): Result[T]
  protected def withConnectionWrapped[T](f: Connection => T): Result[T] =
    withConnection(conn => wrap(f(conn)))

  // Not overridden in JdbcRunContext in Scala2-Quill because this method is not defined in the context
  override def executeAction[T](sql: String, prepare: Prepare = identityPrepare)(executionInfo: ExecutionInfo, dc: DatasourceContext): Result[Long] =
    withConnectionWrapped { conn =>
      val (params, ps) = prepare(conn.prepareStatement(sql), conn)
      logger.logQuery(sql, params)
      ps.executeUpdate().toLong
    }

  // Not overridden in JdbcRunContext in Scala2-Quill because this method is not defined in the context
  override def executeQuery[T](sql: String, prepare: Prepare = identityPrepare, extractor: Extractor[T] = identityExtractor)(executionInfo: ExecutionInfo, dc: DatasourceContext): Result[List[T]] =
    withConnectionWrapped { conn =>
      val (params, ps) = prepare(conn.prepareStatement(sql), conn)
      logger.logQuery(sql, params)
      val rs = ps.executeQuery()
      extractResult(rs, conn, extractor)
    }

  override def executeQuerySingle[T](sql: String, prepare: Prepare = identityPrepare, extractor: Extractor[T] = identityExtractor)(executionInfo: ExecutionInfo, dc: DatasourceContext): Result[T] =
    handleSingleWrappedResult(executeQuery(sql, prepare, extractor)(executionInfo, dc))

  override def executeActionReturning[O](sql: String, prepare: Prepare = identityPrepare, extractor: Extractor[O], returningBehavior: ReturnAction)(executionInfo: ExecutionInfo, dc: DatasourceContext): Result[O] =
    withConnectionWrapped { conn =>
      val (params, ps) = prepare(prepareWithReturning(sql, conn, returningBehavior), conn)
      logger.logQuery(sql, params)
      ps.executeUpdate()
      handleSingleResult(extractResult(ps.getGeneratedKeys, conn, extractor))
    }

  protected def prepareWithReturning(sql: String, conn: Connection, returningBehavior: ReturnAction) =
    returningBehavior match {
      case ReturnRecord           => conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
      case ReturnColumns(columns) => conn.prepareStatement(sql, columns.toArray)
      case ReturnNothing          => conn.prepareStatement(sql)
    }

  override def executeBatchAction(groups: List[BatchGroup])(executionInfo: ExecutionInfo, dc: DatasourceContext): Result[List[Long]] =
    withConnectionWrapped { conn =>
      groups.flatMap {
        case BatchGroup(sql, prepare) =>
          val ps = conn.prepareStatement(sql)
          //logger.underlying.debug("Batch: {}", sql)
          prepare.foreach { f =>
            val (params, _) = f(ps, conn)
            //logger.logBatchItem(sql, params)
            ps.addBatch()
          }
          ps.executeBatch().map(_.toLong)
      }
    }

  def executeBatchActionReturning[T](groups: List[BatchGroupReturning], extractor: Extractor[T])(executionInfo: ExecutionInfo, dc: DatasourceContext): Result[List[T]] =
    withConnectionWrapped { conn =>
      groups.flatMap {
        case BatchGroupReturning(sql, returningBehavior, prepare) =>
          val ps = prepareWithReturning(sql, conn, returningBehavior)
          //logger.underlying.debug("Batch: {}", sql)
          prepare.foreach { f =>
            val (params, _) = f(ps, conn)
            logger.logBatchItem(sql, params)
            ps.addBatch()
          }
          ps.executeBatch()
          extractResult(ps.getGeneratedKeys, conn, extractor)
      }
    }

  protected def handleSingleWrappedResult[T](list: Result[List[T]]): Result[T] =
    push(list)(handleSingleResult(_))

  /**
   * Parses instances of java.sql.Types to string form so it can be used in creation of sql arrays.
   * Some databases does not support each of generic types, hence it's welcome to override this method
   * and provide alternatives to non-existent types.
   *
   * @param intType one of java.sql.Types
   * @return JDBC type in string form
   */
  def parseJdbcType(intType: Int): String = JDBCType.valueOf(intType).getName

  private[getquill] final def extractResult[T](rs: ResultSet, conn: Connection, extractor: Extractor[T]): List[T] =
    ResultSetExtractor(rs, conn, extractor)
}
