package slick.jdbc

import slick.util.TableDump

import scala.collection.compat._
import scala.collection.mutable.ArrayBuffer
import scala.language.reflectiveCalls

import java.io.{InputStream, Reader}
import java.util.Calendar
import java.{sql => js}
import java.sql.{PreparedStatement, Connection, SQLWarning, ResultSet, Statement, Timestamp}

/** A wrapper for `java.sql.Statement` that logs statements and benchmark results
  * to the appropriate [[JdbcBackend]] loggers. */
class LoggingStatement(st: Statement) extends Statement {
  private[this] val doStatement = JdbcBackend.statementLogger.isDebugEnabled
  private[this] val doBenchmark = JdbcBackend.benchmarkLogger.isDebugEnabled
  private[this] val doParameter = JdbcBackend.parameterLogger.isDebugEnabled
  private[this] val doStatementAndParameter = JdbcBackend.statementAndParameterLogger.isDebugEnabled

  private[this] var params: ArrayBuffer[(Any, Any)] = null
  private[this] var paramss: ArrayBuffer[ArrayBuffer[(Any, Any)]] = null

  /** log a parameter */
  protected[this] def p(idx: Int, tpe: Any, value: Any): Unit = if(doParameter) {
    if(params eq null) params = new ArrayBuffer
    if(params.size == idx) params += ((tpe, value))
    else {
      while(params.size < idx) params += null
      params(idx-1) = (tpe, value)
    }
  }

  protected[this] def pushParams: Unit = if(doParameter) {
    if(params ne null) {
      if(paramss eq null) paramss = new ArrayBuffer
      paramss += params
    }
    params = null
  }

  protected[this] def clearParamss: Unit = if(doParameter) {
    paramss = null
    params = null
  }

  protected[this] def logged[T](sql: String, what: String = "statement")(f: =>T) = {
    if(doStatement && (sql ne null)) JdbcBackend.statementLogger.debug("Executing "+what+": "+sql)
    if(doStatementAndParameter && (sql ne null)) JdbcBackend.statementAndParameterLogger.debug("Executing "+what+": "+sql)
    if(doParameter && (paramss ne null) && paramss.nonEmpty) {
      // like s.groupBy but only group adjacent elements and keep the ordering
      def groupBy[T](s: IterableOnce[T])(f: T => AnyRef): IndexedSeq[IndexedSeq[T]] = {
        var current: AnyRef = null
        val b = new ArrayBuffer[ArrayBuffer[T]]
        s.iterator.foreach { v =>
          val id = f(v)
          if(b.isEmpty || current != id) b += ArrayBuffer(v)
          else b.last += v
          current = id
        }
        b.toIndexedSeq.map(_.toIndexedSeq)
      }
      def mkTpStr(tp: Int) = JdbcTypesComponent.typeNames.getOrElse(tp, tp.toString)
      val paramSets = paramss.map { params =>
        (params.map {
          case (tp: Int, _) => mkTpStr(tp)
          case ((tp: Int, scale: Int), _) => mkTpStr(tp)+"("+scale+")"
          case ((tp: Int, tpStr: String), _) => mkTpStr(tp)+": "+tpStr
          case (tpe, _) => tpe.toString
        }, params.map {
          case (_, null) => "NULL"
          case (_, ()) => ""
          case (_, v) =>
            val s = v.toString
            if(s eq null) "NULL" else s
        })
      }
      val dump = new TableDump(25)
      groupBy(paramSets)(_._1).foreach { matchingSets =>
        val tpes = matchingSets.head._1
        val idxs = 1.to(tpes.length).map(_.toString)
        dump(Vector(idxs, tpes.toIndexedSeq), matchingSets.map(_._2).toIndexedSeq.map(_.toIndexedSeq)).foreach(s => JdbcBackend.parameterLogger.debug(s))
      }
    }
    val t0 = if(doBenchmark) System.nanoTime() else 0L
    val res = f
    if(doBenchmark) JdbcBackend.benchmarkLogger.debug("Execution of "+what+" took "+formatNS(System.nanoTime()-t0))
    clearParamss
    res
  }

  private[this] def formatNS(ns: Long): String = {
    if(ns < 1000L) s"${ns}ns"
    else if(ns < 1000000L) s"${(ns / 1000L)}µs"
    else if(ns < 1000000000L) s"${(ns / 1000000L)}ms"
    else s"${(ns / 1000000000L)}s"
  }

  def addBatch(sql: String) = {
    if(doStatement) JdbcBackend.statementLogger.debug("Adding to batch: "+sql)
    if(doStatementAndParameter) JdbcBackend.statementAndParameterLogger.debug("Adding to batch: "+sql)
    st.addBatch(sql)
  }
  def execute(sql: String, columnNames: Array[String]): Boolean = logged(sql) { st.execute(sql, columnNames) }
  def execute(sql: String, columnIndexes: Array[Int]): Boolean = logged(sql) { st.execute(sql, columnIndexes) }
  def execute(sql: String, autoGeneratedKeys: Int): Boolean = logged(sql) { st.execute(sql, autoGeneratedKeys) }
  def execute(sql: String): Boolean = logged(sql) { st.execute(sql) }
  def executeQuery(sql: String): ResultSet = logged(sql, "query") { st.executeQuery(sql) }
  def executeUpdate(sql: String, columnNames: Array[String]): Int = logged(sql, "update") { st.executeUpdate(sql, columnNames) }
  def executeUpdate(sql: String, columnIndexes: Array[Int]): Int = logged(sql, "update") { st.executeUpdate(sql, columnIndexes) }
  def executeUpdate(sql: String, autoGeneratedKeys: Int): Int = logged(sql, "update") { st.executeUpdate(sql, autoGeneratedKeys) }
  def executeUpdate(sql: String): Int = logged(sql, "update") { st.executeUpdate(sql) }
  def executeBatch(): Array[Int] = logged(null, "batch") { st.executeBatch() }

  def setMaxFieldSize(max: Int) = st.setMaxFieldSize(max)
  def clearWarnings() = st.clearWarnings()
  def getMoreResults(current: Int) = st.getMoreResults(current)
  def getMoreResults: Boolean = st.getMoreResults
  def getGeneratedKeys: ResultSet = st.getGeneratedKeys
  def cancel() = st.cancel()
  def getResultSet: ResultSet = st.getResultSet
  def setPoolable(poolable: Boolean) = st.setPoolable(poolable)
  def isPoolable: Boolean = st.isPoolable
  def setCursorName(name: String) = st.setCursorName(name)
  def getUpdateCount: Int = st.getUpdateCount
  def getMaxRows: Int = st.getMaxRows
  def getResultSetType: Int = st.getResultSetType
  def unwrap[T](iface: Class[T]): T = st.unwrap(iface)
  def setMaxRows(max: Int) = st.setMaxRows(max)
  def getFetchSize: Int = st.getFetchSize
  def getResultSetHoldability: Int = st.getResultSetHoldability
  def setFetchDirection(direction: Int) = st.setFetchDirection(direction)
  def getFetchDirection: Int = st.getFetchDirection
  def getResultSetConcurrency: Int = st.getResultSetConcurrency
  def isWrapperFor(iface: Class[_]): Boolean = st.isWrapperFor(iface)
  def clearBatch() = st.clearBatch()
  def close() = st.close()
  def isClosed: Boolean = st.isClosed
  def getWarnings: SQLWarning = st.getWarnings
  def getQueryTimeout: Int = st.getQueryTimeout
  def setQueryTimeout(seconds: Int) = st.setQueryTimeout(seconds)
  def setFetchSize(rows: Int) = st.setFetchSize(rows)
  def setEscapeProcessing(enable: Boolean) = st.setEscapeProcessing(enable)
  def getConnection: Connection = st.getConnection
  def getMaxFieldSize: Int = st.getMaxFieldSize
  def closeOnCompletion(): Unit =
    st.asInstanceOf[{ def closeOnCompletion(): Unit }].closeOnCompletion()
  def isCloseOnCompletion(): Boolean =
    st.asInstanceOf[{ def isCloseOnCompletion(): Boolean }].isCloseOnCompletion()
}

/** A wrapper for `java.sql.PreparedStatement` that logs statements, parameters and benchmark results
  * to the appropriate [[JdbcBackend]] loggers. */
class LoggingPreparedStatement(st: PreparedStatement) extends LoggingStatement(st) with PreparedStatement {

  def execute(): Boolean = {
    pushParams
    if (JdbcBackend.statementAndParameterLogger.isDebugEnabled) {
      logged(st.toString, "prepared statement") { st.execute() }
    } else {
      logged(null, "prepared statement") { st.execute() }
    }
  }
  def executeQuery(): js.ResultSet = { pushParams; logged(null, "prepared query") { st.executeQuery() } }

  def executeUpdate(): Int = {
    pushParams
    if (JdbcBackend.statementAndParameterLogger.isDebugEnabled) {
      logged(st.toString, "prepared update") { st.executeUpdate() }
    } else {
      logged(null, "prepared update") { st.executeUpdate() }
    }
  }

  def addBatch(): Unit = {
    pushParams
    if (JdbcBackend.statementAndParameterLogger.isDebugEnabled) {
      logged(st.toString, "batch insert") { st.addBatch() }
    } else {
      st.addBatch()
    }
  }
  def clearParameters(): Unit = { clearParamss; st.clearParameters() }

  def getMetaData(): js.ResultSetMetaData = st.getMetaData
  def getParameterMetaData(): js.ParameterMetaData = st.getParameterMetaData()

  // printable parameters
  def setArray           (i: Int, v: js.Array              ): Unit = { p(i, "Array",             v); st.setArray(i, v) }
  def setBigDecimal      (i: Int, v: java.math.BigDecimal  ): Unit = { p(i, "BigDecimal",        v); st.setBigDecimal(i, v) }
  def setBoolean         (i: Int, v: Boolean               ): Unit = { p(i, "Boolean",           v); st.setBoolean(i, v) }
  def setByte            (i: Int, v: Byte                  ): Unit = { p(i, "Byte",              v); st.setByte(i, v) }
  def setBytes           (i: Int, v: Array[Byte]           ): Unit = { p(i, "Bytes",             v); st.setBytes(i, v) }
  def setDate            (i: Int, v: js.Date, c: Calendar  ): Unit = { p(i, "Date",         (v, c)); st.setDate(i, v, c) }
  def setDate            (i: Int, v: js.Date               ): Unit = { p(i, "Date",              v); st.setDate(i, v) }
  def setDouble          (i: Int, v: Double                ): Unit = { p(i, "Double",            v); st.setDouble(i, v) }
  def setFloat           (i: Int, v: Float                 ): Unit = { p(i, "Float",             v); st.setFloat(i, v) }
  def setInt             (i: Int, v: Int                   ): Unit = { p(i, "Int",               v); st.setInt(i, v) }
  def setLong            (i: Int, v: Long                  ): Unit = { p(i, "Long",              v); st.setLong(i, v) }
  def setNString         (i: Int, v: String                ): Unit = { p(i, "NString",           v); st.setNString(i, v) }
  def setNull            (i: Int, tp: Int, tpStr: String   ): Unit = { p(i, (tp, tpStr),      null); st.setNull(i, tp, tpStr) }
  def setNull            (i: Int, tp: Int                  ): Unit = { p(i, tp,               null); st.setNull(i, tp) }
  def setObject          (i: Int, v: Any, tp: Int, scl: Int): Unit = { p(i, (tp, scl),           v); st.setObject(i, v, tp, scl) }
  def setObject          (i: Int, v: Any, tp: Int          ): Unit = { p(i, tp,                  v); st.setObject(i, v, tp) }
  def setObject          (i: Int, v: Any                   ): Unit = { p(i, "Object",            v); st.setObject(i, v) }
  def setRef             (i: Int, v: js.Ref                ): Unit = { p(i, "Ref",               v); st.setRef(i, v) }
  def setRowId           (i: Int, v: js.RowId              ): Unit = { p(i, "RowId",             v); st.setRowId(i, v) }
  def setSQLXML          (i: Int, v: js.SQLXML             ): Unit = { p(i, "SQLXML",            v); st.setSQLXML(i, v) }
  def setShort           (i: Int, v: Short                 ): Unit = { p(i, "Short",             v); st.setShort(i, v) }
  def setString          (i: Int, v: String                ): Unit = { p(i, "String",            v); st.setString(i, v) }
  def setTime            (i: Int, v: js.Time, c: Calendar  ): Unit = { p(i, "Time",         (v, c)); st.setTime(i, v, c) }
  def setTime            (i: Int, v: js.Time               ): Unit = { p(i, "Time",              v); st.setTime(i, v) }
  def setTimestamp       (i: Int, v: Timestamp, c: Calendar): Unit = { p(i, "Timestamp",    (v, c)); st.setTimestamp(i, v, c) }
  def setTimestamp       (i: Int, v: Timestamp             ): Unit = { p(i, "Timestamp",         v); st.setTimestamp(i, v) }
  def setURL             (i: Int, v: java.net.URL          ): Unit = { p(i, "URL",               v); st.setURL(i, v) }

  // hidden parameters
  def setAsciiStream     (i: Int, v: InputStream           ): Unit = { p(i, "AsciiStream",      ()); st.setAsciiStream(i, v) }
  def setAsciiStream     (i: Int, v: InputStream, len: Long): Unit = { p(i, "AsciiStream",      ()); st.setAsciiStream(i, v, len) }
  def setAsciiStream     (i: Int, v: InputStream, len: Int ): Unit = { p(i, "AsciiStream",      ()); st.setAsciiStream(i, v, len) }
  def setBinaryStream    (i: Int, v: InputStream           ): Unit = { p(i, "BinaryStream",     ()); st.setBinaryStream(i, v) }
  def setBinaryStream    (i: Int, v: InputStream, len: Long): Unit = { p(i, "BinaryStream",     ()); st.setBinaryStream(i, v, len) }
  def setBinaryStream    (i: Int, v: InputStream, len: Int ): Unit = { p(i, "BinaryStream",     ()); st.setBinaryStream(i, v, len) }
  def setBlob            (i: Int, v: InputStream           ): Unit = { p(i, "Blob",             ()); st.setBlob(i, v) }
  def setBlob            (i: Int, v: InputStream, len: Long): Unit = { p(i, "Blob",             ()); st.setBlob(i, v, len) }
  def setBlob            (i: Int, v: js.Blob               ): Unit = { p(i, "Blob",             ()); st.setBlob(i, v) }
  def setCharacterStream (i: Int, v: Reader                ): Unit = { p(i, "CharacterStream",  ()); st.setCharacterStream(i, v) }
  def setCharacterStream (i: Int, v: Reader, len: Long     ): Unit = { p(i, "CharacterStream",  ()); st.setCharacterStream(i, v, len) }
  def setCharacterStream (i: Int, v: Reader, len: Int      ): Unit = { p(i, "CharacterStream",  ()); st.setCharacterStream(i, v, len) }
  def setClob            (i: Int, v: Reader                ): Unit = { p(i, "Clob",             ()); st.setClob(i, v) }
  def setClob            (i: Int, v: Reader, len: Long     ): Unit = { p(i, "Clob",             ()); st.setClob(i, v, len) }
  def setClob            (i: Int, v: js.Clob               ): Unit = { p(i, "Clob",             ()); st.setClob(i, v) }
  def setNCharacterStream(i: Int, v: Reader                ): Unit = { p(i, "NCharacterStream", ()); st.setNCharacterStream(i, v) }
  def setNCharacterStream(i: Int, v: Reader, len: Long     ): Unit = { p(i, "NCharacterStream", ()); st.setNCharacterStream(i, v, len) }
  def setNClob           (i: Int, v: Reader                ): Unit = { p(i, "NClob",            ()); st.setNClob(i, v) }
  def setNClob           (i: Int, v: Reader, len: Long     ): Unit = { p(i, "NClob",            ()); st.setNClob(i, v, len) }
  def setNClob           (i: Int, v: js.NClob              ): Unit = { p(i, "NClob",            ()); st.setNClob(i, v) }
  @deprecated("setUnicodeStream is deprecated", "")
  def setUnicodeStream   (i: Int, v: InputStream, len: Int ): Unit = { p(i, "UnicodeStream",    ()); st.setUnicodeStream(i, v, len) }
}
