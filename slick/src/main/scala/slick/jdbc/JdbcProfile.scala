package slick.jdbc

import scala.collection.mutable.Builder
import scala.language.{implicitConversions, higherKinds}

import slick.ast._
import slick.ast.TypeUtil.:@
import slick.compiler.{Phase, QueryCompiler, InsertCompiler}
import slick.lifted._
import slick.relational.{RelationalProfile, CompiledMapping}
import slick.sql.SqlProfile

/** Abstract profile for accessing SQL databases via JDBC. */
trait JdbcProfile extends SqlProfile with JdbcActionComponent
  with JdbcInvokerComponent with JdbcTypesComponent with JdbcModelComponent
  /* internal: */ with JdbcStatementBuilderComponent with JdbcMappingCompilerComponent {

  @deprecated("Use the Profile object directly instead of calling `.profile` on it", "3.2")
  override val profile: JdbcProfile = this

  type Backend = JdbcBackend
  val backend: Backend = JdbcBackend
  type ColumnType[T] = JdbcType[T]
  type BaseColumnType[T] = JdbcType[T] with BaseTypedType[T]
  val columnTypes = new JdbcTypes
  lazy val MappedColumnType = MappedJdbcType

  override protected def computeCapabilities = super.computeCapabilities ++ JdbcCapabilities.all

  lazy val queryCompiler = compiler + new JdbcCodeGen(_.buildSelect())
  lazy val updateCompiler = compiler + new JdbcCodeGen(_.buildUpdate())
  lazy val deleteCompiler = compiler + new JdbcCodeGen(_.buildDelete())
  lazy val insertCompiler = QueryCompiler(Phase.assignUniqueSymbols, Phase.inferTypes, new InsertCompiler(InsertCompiler.NonAutoInc), new JdbcInsertCodeGen(createInsertBuilder))
  lazy val forceInsertCompiler = QueryCompiler(Phase.assignUniqueSymbols, Phase.inferTypes, new InsertCompiler(InsertCompiler.AllColumns), new JdbcInsertCodeGen(createInsertBuilder))
  lazy val upsertCompiler = QueryCompiler(Phase.assignUniqueSymbols, Phase.inferTypes, new InsertCompiler(InsertCompiler.AllColumns), new JdbcInsertCodeGen(createUpsertBuilder))
  lazy val checkInsertCompiler = QueryCompiler(Phase.assignUniqueSymbols, Phase.inferTypes, new InsertCompiler(InsertCompiler.PrimaryKeys), new JdbcInsertCodeGen(createCheckInsertBuilder))
  lazy val updateInsertCompiler = QueryCompiler(Phase.assignUniqueSymbols, Phase.inferTypes, new InsertCompiler(InsertCompiler.AllColumns), new JdbcInsertCodeGen(createUpdateInsertBuilder))
  def compileInsert(tree: Node) = new JdbcCompiledInsert(tree)
  type CompiledInsert = JdbcCompiledInsert

  final def buildTableSchemaDescription(table: Table[_]): DDL = createTableDDLBuilder(table).buildDDL
  final def buildSequenceSchemaDescription(seq: Sequence[_]): DDL = createSequenceDDLBuilder(seq).buildDDL

  trait JdbcLowPriorityAPI {
    implicit def queryUpdateActionExtensionMethods[U, C[_]](q: Query[_, U, C]): UpdateActionExtensionMethodsImpl[U] =
      createUpdateActionExtensionMethods(updateCompiler.run(q.toNode).tree, ())
  }

  trait JdbcAPI extends JdbcLowPriorityAPI with RelationalAPI with JdbcImplicitColumnTypes {
    type SimpleDBIO[+R] = SimpleJdbcAction[R]
    val SimpleDBIO = SimpleJdbcAction

    implicit def queryDeleteActionExtensionMethods[C[_]](q: Query[_ <: RelationalProfile#Table[_], _, C]): DeleteActionExtensionMethods =
      createDeleteActionExtensionMethods(deleteCompiler.run(q.toNode).tree, ())

    implicit def mergeActionExtensionMethods[E, U, C[_]](q: Query[E, U, C]): MergeActionExtensionMethods[E] = {
      val preparer: MergePreparer[E] = { f =>
        val generator = new AnonSymbol
        val aliased = q.shaped.encodeRef(Ref(generator)).value
        val builder = new MergeBuilder[E](aliased, new SimpleJdbcTypeFieldSetterFactory())

        f(builder)

        if (builder.isEmpty) {
          None
        } else {
          var ordering: Seq[FieldSymbol] = Seq.empty
          val filterAndTrace = (field: FieldSymbol) => {
            val dirty = builder.isDirty(field)
            if (dirty) ordering :+= field
            dirty
          }
          val filteredNodes = q.toNode.withChildren {
            q.toNode.children.map {
              case TableExpansion(generator, table, columns) =>
                val newColumns = columns.withChildren {
                  columns.children.filter {
                    case Select(_, field: FieldSymbol) => filterAndTrace(field)
                    case OptionApply(Select(_, field: FieldSymbol)) => filterAndTrace(field)
                    case _ => false
                  }
                }
                TableExpansion(generator, table, newColumns)
              case ProductNode(children) =>
                val newColumns = children.filter {
                  case Select(_, field: FieldSymbol) => filterAndTrace(field)
                  case OptionApply(Select(_, field: FieldSymbol)) => filterAndTrace(field)
                  case _ => false
                }
                ProductNode(newColumns)
              case others => others
            }
          }

          val complied = updateCompiler.run(filteredNodes)
          Some((complied.tree, ordering.zipWithIndex.reverse.toMap, builder))
        }
      }
      createMergeActionExtensionMethods(preparer)
    }

    implicit def runnableCompiledDeleteActionExtensionMethods[RU, C[_]](c: RunnableCompiled[_ <: Query[_, _, C], C[RU]]): DeleteActionExtensionMethods =
      createDeleteActionExtensionMethods(c.compiledDelete, c.param)

    implicit def runnableCompiledUpdateActionExtensionMethods[RU, C[_]](c: RunnableCompiled[_ <: Query[_, _, C], C[RU]]): UpdateActionExtensionMethods[RU] =
      createUpdateActionExtensionMethods(c.compiledUpdate, c.param)

    implicit def jdbcActionExtensionMethods[E <: Effect, R, S <: NoStream](a: DBIOAction[R, S, E]): JdbcActionExtensionMethods[E, R, S] =
      new JdbcActionExtensionMethods[E, R, S](a)

    implicit def actionBasedSQLInterpolation(s: StringContext): ActionBasedSQLInterpolation = new ActionBasedSQLInterpolation(s)
  }

  val api: JdbcAPI = new JdbcAPI {}

  def runSynchronousQuery[R](tree: Node, param: Any)(implicit session: Backend#Session): R = tree match {
    case rsm @ ResultSetMapping(_, _, CompiledMapping(_, elemType)) :@ CollectionType(cons, el) =>
      val b = cons.createBuilder(el.classTag).asInstanceOf[Builder[Any, R]]
      createQueryInvoker[Any](rsm, param, null).foreach({ x => b += x }, 0)(session)
      b.result()
    case First(rsm: ResultSetMapping) =>
      createQueryInvoker[R](rsm, param, null).first
  }
}
