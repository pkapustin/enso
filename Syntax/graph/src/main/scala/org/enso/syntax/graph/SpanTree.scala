package org.enso.syntax.graph

import org.enso.syntax.graph.AstOps._
import org.enso.syntax.text.AST
import org.enso.syntax.text.AST.Opr
import org.enso.syntax.text.ast.meta.Pattern

import scala.util.Success
import scala.util.Try

/** A GUI-friendly structure describing expression with nodes mapped to
  * expression text spans.
  *
  * SpanTree flattens nested applications into flat children lists and describes
  * possible actions, i.e. which nodes can be replaced when creating a
  * connection or where a new variable can be inserted.
  *
  * It is expected that this structure facilitates, with possible help of
  * additional layers, the following GUI parts:
  *  - expression editor and its behavior when creating a connection
  *  - node inputs
  *  - node outputs
  *
  * This structure can be considered a layer over AST that does not introduce
  * any new data, it just presents AST in more accessible form.
  */
sealed trait SpanTree {

  import SpanTree._

  /** The text of expression part being described by this node. May be empty, if
    * this node represents just a point in text.
    */
  def text: String

  /** The span of expression part being described by this node. Indices are
    * relative to the beginning of the expression described by the tree root.
    *
    * For some nodes, like [[SpanTree.EmptyEndpoint]] span can be of zero
    * length — in such case they only represent a point within a string.
    */
  def span: TextSpan

  /** Sequence of children and operations that can be performed on them. */
  def describeChildren: Seq[NodeInfo]

  /** Children nodes. */
  def children: Seq[SpanTree] = describeChildren.map(_.spanTree)

  /** Index of the first character in the [[span]]. */
  def begin: TextPosition = span.begin

  /** Index of the first character after the [[span]] */
  def end: TextPosition = span.end

  /** Get node by traversing given path. */
  def get(path: Path): Try[SpanTree] = path match {
    case Seq() => Success(this)
    case topIndex :: tailIndices =>
      val tryChild = Try { children(topIndex) }
      tryChild.flatMap(_.get(tailIndices))
  }

  /** Calls function `f` for all nodes in the tree. */
  def foreach[U](f: NodeInfoWithPath => U): Unit = {
    def go(node: SpanTree, pathSoFar: Path): Unit =
      node.describeChildren.zipWithIndex.foreach {
        case (childInfo, index) =>
          val childPath = pathSoFar :+ index
          f(childInfo.addPath(childPath))
          go(childInfo.spanTree, childPath)
      }

    f(NodeInfoWithPath(this, RootPath, Actions.Root))
    go(this, RootPath)
  }

  def foldLeft[B](z: B)(op: (B, SpanTree.NodeInfoWithPath) => B): B = {
    var result = z
    foreach { nodeInfo =>
      result = op(result, nodeInfo)
    }
    result
  }

  def toSeq: Seq[NodeInfoWithPath] = foldLeft(Seq[NodeInfoWithPath]()) {
    (acc, node) =>
      node +: acc
  }
}

object SpanTree {

  /** Actions available for functions, i.e. application targets and infix
    * operators. While this API level allows setting functions, this capability
    * will likely be blocked in the GUI.
    */
  val functionActions: Set[Action] = Set(Action.Set)

  val rootActions: Set[Action] = Set[Action](Action.Set)

  /** Just a [[SpanTree]] with a sequence of [[Action]] that are supported for
    * it.
    */
  trait NodeInfoCommon {
    val spanTree: SpanTree
    val actions: Set[Action]

    def supports(action: Action): Boolean = actions.contains(action)
    def settable: Boolean                 = supports(Action.Set)
    def erasable: Boolean                 = supports(Action.Erase)
    def insertable: Boolean               = supports(Action.Insert)
  }

  /** Type used to describe node's children and actions it can be target of.
    * @see [[SpanTree.describeChildren]]
    */
  final case class NodeInfo(
    spanTree: SpanTree,
    actions: Set[Action] = Set()
  ) extends NodeInfoCommon {
    def addPath(path: SpanTree.Path): NodeInfoWithPath =
      NodeInfoWithPath(spanTree, path, actions)
  }

  /** Like [[NodeInfo]] but with [[Path]] - so it also allows to uniquely
    * denote the node position in the tree.
    */
  final case class NodeInfoWithPath(
    spanTree: SpanTree,
    path: SpanTree.Path,
    actions: Set[Action] = Set()
  ) extends NodeInfoCommon

  object NodeInfo {

    /** Create info for node that supports only a single [[Action]]. */
    def apply(spanTree: SpanTree, action: Action): NodeInfo =
      NodeInfo(spanTree, Set(action))

    /** Create info for node that supports all 3 [[Action]]s. */
    def withAllActions(spanTree: SpanTree): NodeInfo =
      NodeInfo(spanTree, Set(Action.Set, Action.Erase, Action.Insert))

    /** Create info for node that supports only [[Set]] [[Action]]. */
    def insertionPoint(position: TextPosition): NodeInfo =
      NodeInfo(EmptyEndpoint(position), Action.Insert)
  }

  /** Node that represent some non-empty AST. */
  final case class AstNodeInfo(
    position: TextPosition,
    ast: AST
  ) {}

  /** Node denoting a location that could contain some value but currently is
    * empty. Its span has length zero and it is not paired with any
    * AST. Typically it supports only [[Action.Insert]].
    *
    * E.g. `+` is an operator with two empty endpoints.
    */
  final case class EmptyEndpoint(position: TextPosition)
      extends SpanTree
      with Leaf {
    override def text: String   = ""
    override def span: TextSpan = TextSpan.Empty(position)
  }

  /** Node describing certain AST subtree, has non-zero span. */
  sealed trait AstNode extends SpanTree {
    def info: AstNodeInfo
    def ast: AST       = info.ast
    def text: String   = ast.show()
    def span: TextSpan = TextSpan(info.position, TextLength(ast))

  }

  /** Generalization over prefix application (e.g. `print "Hello"`) and
    * infix operator application (e.g. `a + b`).
    */
  abstract class ApplicationLike extends AstNode {}

  /** E.g. `a + b + c` flattened to a single 5-child node. Operands can be
    * set and erased. New operands can be inserted next to existing operands.
    *
    * @param opr The infix operator on which we apply operands. If there are
    *            multiple operators in chain, any of them might be referenced.
    * @param leftmostOperand The left-most operand and the first input.
    * @param parts Subsequent pairs of children (operator, operand).
    */
  case class OperatorChain(
    info: AstNodeInfo,
    opr: Opr,
    leftmostOperand: SpanTree,
    parts: Seq[(AstLeaf, SpanTree)]
  ) extends ApplicationLike {

    private def describeOperand(operand: SpanTree): NodeInfo = operand match {
      case _: EmptyEndpoint =>
        NodeInfo(operand, Action.Insert)
      case _ =>
        NodeInfo(operand, Actions.All)
    }

    override def describeChildren: Seq[SpanTree.NodeInfo] = {
      val leftmostOperandInfo = describeOperand(leftmostOperand)
      val childrenInfos = parts.foldLeft(Seq(leftmostOperandInfo)) {
        case (acc, (oprAtom, operand)) =>
          val operatorInfo = NodeInfo(oprAtom, functionActions)
          val operandInfo  = describeOperand(operand)
          acc :+ operatorInfo :+ operandInfo
      }

      // If we already miss last the argument, don't add additional placeholder.
      val insertAfterLast = childrenInfos.lastOption.map(_.spanTree) match {
        case Some(EmptyEndpoint(_)) => None
        case _                      => Some(NodeInfo.insertionPoint(end))
      }

      childrenInfos ++ insertAfterLast
    }
  }

  /** E.g. `foo bar baz` flattened to a single 3-child node.
    *
    * The first child is a function name (calleee).
    * The second child is a "self" port.
    * Any additional children are flattened further arguments.
    */
  case class ApplicationChain(
    info: AstNodeInfo,
    callee: SpanTree,
    arguments: Seq[SpanTree]
  ) extends ApplicationLike {
    override def describeChildren: Seq[SpanTree.NodeInfo] = {
      val calleeInfo           = NodeInfo(callee, functionActions)
      val argumentsInfo        = arguments.map(NodeInfo.withAllActions)
      val potentialNewArgument = NodeInfo.insertionPoint(end)
      calleeInfo +: argumentsInfo :+ potentialNewArgument
    }
  }

  /** Helper trait to facilitate recognizing leaf nodes in span tree. */
  sealed trait Leaf {
    this -> SpanTree
    def describeChildren: Seq[SpanTree.NodeInfo] = Seq()
  }

  /** A leaf representing an AST element that cannot be decomposed any
    * further.
    */
  final case class AstLeaf(info: AstNodeInfo) extends AstNode with Leaf
  object AstLeaf {
    def apply(textPosition: TextPosition, ast: AST): SpanTree.AstLeaf =
      AstLeaf(AstNodeInfo(textPosition, ast))
  }

  /** Node representing a macro usage. */
  final case class MacroMatch(
    info: AstNodeInfo,
    prefix: Option[MacroSegment],
    segments: Seq[MacroSegment]
  ) extends AstNode {
    override def describeChildren: Seq[NodeInfo] = {
      prefix.map(NodeInfo(_)).toSeq ++ segments.map(NodeInfo(_))
    }
  }

  /** Node representing a macro prefix or segment. [[introducer]] is empty iff
    * represented node was prefix. Otherwise, it contains segment's head name.
    */
  final case class MacroSegment(
    override val begin: TextPosition,
    introducer: Option[String],
    patternMatch: Pattern.Match,
    override val describeChildren: Seq[NodeInfo]
  ) extends SpanTree {
    override def span: TextSpan = {
      val introLength = TextLength(introducer.map(_.length).getOrElse(0))
      TextSpan(begin, introLength + TextLength(patternMatch))
    }
    override def text: String =
      patternMatch.toStream.foldLeft(introducer.getOrElse(""))(
        (s, ast) => s + (" " * ast.off) + ast.el.show()
      )
  }

  /** An index in the sequence returned by [[SpanTree.children]] method. */
  type ChildIndex = Int

  /** A sequence of indices, describes a path through a [[SpanTree]].
    * @see [[ChildIndex]]
    */
  type Path = Seq[ChildIndex]

  /** [[Path]] to the root of the [[SpanTree]], i.e. empty path. */
  val RootPath: Path = Seq()

  //////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////
  // BUILDING SPAN TREE CODE BELOW
  //////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////

  def apply(pos: TextPosition, s: AST.Macro.Match.Segment): MacroSegment = {
    import Ops._
    val bodyPos  = pos + TextLength(s.head)
    var children = patternStructure(bodyPos, s.body)
    if (s.body.pat.canBeNothing)
      children = children.map { child =>
        child.copy(actions = child.actions + Action.Erase)
      }
    MacroSegment(pos, Some(s.head.show()), s.body, children)
  }

  def apply(ast: AST, pos: TextPosition): SpanTree = ast match {
    case AST.Opr.any(opr)          => AstLeaf(pos, opr)
    case AST.Blank.any(_)          => AstLeaf(pos, ast)
    case AST.Literal.Number.any(_) => AstLeaf(pos, ast)
    case AST.Var.any(_)            => AstLeaf(pos, ast)
    case AST.App.Prefix.any(app) =>
      val info         = AstNodeInfo(pos, ast)
      val childrenAsts = ast.flattenPrefix(pos, app)
      val childrenNodes = childrenAsts.map {
        case (childPos, childAst) =>
          SpanTree(childAst, childPos)
      }
      childrenNodes match {
        case callee :: args =>
          ApplicationChain(info, callee, args)
        case _ =>
          // app prefix always has two children, flattening can only add more
          throw new Exception("impossible: failed to find application target")
      }

    case m @ AST.Macro.Match(optPrefix, segments, resolved) =>
      val optPrefixNode = optPrefix.map { m =>
        val children = patternStructure(pos, m)
        MacroSegment(pos, None, m, children)
      }
      var i = pos + optPrefixNode
          .map(_.span.length)
          .getOrElse(TextLength.Empty)

      val segmentNodes = segments.toList(0).map { s =>
        val node = SpanTree(i + s.off, s.el)
        i += node.span.length + TextLength(s.off)
        node
      }

      MacroMatch(AstNodeInfo(pos, m), optPrefixNode, segmentNodes)

    case _ =>
      GeneralizedInfix(ast) match {
        case Some(info) =>
          val childrenAsts = info.flattenInfix(pos)
          val childrenNodes = childrenAsts.map {
            case part: ExpressionPart =>
              SpanTree(part.ast, part.pos)
            case part: EmptyPlace =>
              SpanTree.EmptyEndpoint(part.pos)
          }

          val nodeInfo = AstNodeInfo(pos, ast)

          val self = childrenNodes.headOption.getOrElse(
            throw new Exception(
              "internal error: infix with no children nodes"
            )
          )
          val calls = childrenNodes
            .drop(1)
            .sliding(2, 2)
            .map {
              case (opr: AstLeaf) :: (arg: SpanTree) :: Nil =>
                (opr, arg)
            }
            .toSeq

          OperatorChain(nodeInfo, info.operatorAst, self, calls)
        case _ =>
          println("failed to generate span tree node for " + ast.show())
          println(ast.toString())
          throw new Exception("internal error: not supported ast")
      }
  }
  def patternStructure(
    pos: TextPosition,
    patMatch: Pattern.Match
  ): Seq[NodeInfo] = patMatch match {
    case Pattern.Match.Or(pat, elem) =>
      patternStructure(pos, elem.fold(identity, identity))
    case Pattern.Match.Seq(pat, elems) =>
      val left       = patternStructure(pos, elems._1)
      val leftLength = TextLength(elems._1)
      val right      = patternStructure(pos + leftLength, elems._2)
      left ++ right
    case Pattern.Match.Build(_, elem) =>
      val node = SpanTree(elem.el, pos + elem.off)
      Seq(NodeInfo(node, Action.Set))
    case Pattern.Match.End(_) =>
      Seq()
    case Pattern.Match.Nothing(_) =>
      Seq()
    case a =>
      println(a)
      null
  }
}