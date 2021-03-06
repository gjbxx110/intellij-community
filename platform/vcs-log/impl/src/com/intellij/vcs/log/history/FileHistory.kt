// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.vcs.log.history

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Couple
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.UnorderedPair
import com.intellij.openapi.vcs.FilePath
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.MultiMap
import com.intellij.util.containers.Stack
import com.intellij.vcs.log.data.index.VcsLogPathsIndex
import com.intellij.vcs.log.data.index.VcsLogPathsIndex.ChangeKind
import com.intellij.vcs.log.graph.api.LinearGraph
import com.intellij.vcs.log.graph.api.LiteLinearGraph
import com.intellij.vcs.log.graph.api.permanent.PermanentCommitsInfo
import com.intellij.vcs.log.graph.api.permanent.PermanentGraphInfo
import com.intellij.vcs.log.graph.collapsing.CollapsedGraph
import com.intellij.vcs.log.graph.impl.facade.*
import com.intellij.vcs.log.graph.utils.BfsUtil.getCorrespondingParent
import com.intellij.vcs.log.graph.utils.DfsUtil
import com.intellij.vcs.log.graph.utils.LinearGraphUtils
import com.intellij.vcs.log.graph.utils.impl.BitSetFlags
import gnu.trove.TIntObjectHashMap
import java.util.*
import java.util.function.BiConsumer

internal class FileHistoryBuilder(private val startCommit: Int?,
                                  private val startPath: FilePath,
                                  private val fileNamesData: FileNamesData) : BiConsumer<LinearGraphController, PermanentGraphInfo<Int>> {
  val pathsMap = mutableMapOf<Int, MaybeDeletedFilePath>()

  override fun accept(controller: LinearGraphController, permanentGraphInfo: PermanentGraphInfo<Int>) {
    pathsMap.putAll(refine(controller, startCommit, permanentGraphInfo))

    val trivialCandidates = mutableSetOf<Int>()
    pathsMap.forEach { c, p ->
      if (fileNamesData.isTrivialMerge(c, p.filePath)) {
        trivialCandidates.add(c)
      }
    }

    modifyGraph(controller) { collapsedGraph ->
      val trivialMerges = hideTrivialMerges(collapsedGraph) { nodeId: Int ->
        trivialCandidates.contains(permanentGraphInfo.permanentCommitsInfo.getCommitId(nodeId))
      }
      trivialMerges.forEach { pathsMap.remove(permanentGraphInfo.permanentCommitsInfo.getCommitId(it)) }
    }
  }

  private fun refine(controller: LinearGraphController,
                     startCommit: Int?,
                     permanentGraphInfo: PermanentGraphInfo<Int>): Map<Int, MaybeDeletedFilePath> {
    if (fileNamesData.hasRenames) {
      val visibleLinearGraph = controller.compiledGraph

      val (row, path) = startCommit?.let {
        findAncestorRowAffectingFile(startCommit, visibleLinearGraph, permanentGraphInfo)
      } ?: Pair(0, MaybeDeletedFilePath(startPath))
      if (row >= 0) {
        val refiner = FileHistoryRefiner(visibleLinearGraph, permanentGraphInfo, fileNamesData)
        val (paths, excluded) = refiner.refine(row, path)
        if (!excluded.isEmpty()) {
          val hidden = hideCommits(controller, permanentGraphInfo, excluded)
          if (!hidden) LOG.error("Could not hide excluded commits from history for " + startPath.path)
        }
        return paths
      }
    }
    return fileNamesData.buildPathsMap()
  }

  private fun findAncestorRowAffectingFile(commitId: Int,
                                           visibleLinearGraph: LinearGraph,
                                           permanentGraphInfo: PermanentGraphInfo<Int>): Pair<Int, MaybeDeletedFilePath> {
    val existing = MaybeDeletedFilePath(startPath)
    val deleted = MaybeDeletedFilePath(startPath, true)
    val isDeleted: Ref<Boolean> = Ref.create(false)
    val row = findVisibleAncestorRow(commitId, visibleLinearGraph, permanentGraphInfo) { nodeId ->
      val id = permanentGraphInfo.permanentCommitsInfo.getCommitId(nodeId)
      when {
        fileNamesData.affects(id, existing) -> true
        fileNamesData.affects(id, deleted) -> {
          isDeleted.set(true)
          true
        }
        else -> false
      }
    } ?: -1
    return Pair(row, if (isDeleted.get()) deleted else existing)
  }

  companion object {
    private val LOG = Logger.getInstance(FileHistoryBuilder::class.java)
  }
}

fun hideTrivialMerges(collapsedGraph: CollapsedGraph,
                      isCandidateNodeId: (Int) -> Boolean): Set<Int> {
  val result = mutableSetOf<Int>()
  val graph = LinearGraphUtils.asLiteLinearGraph(collapsedGraph.compiledGraph)

  outer@ for (v in graph.nodesCount() - 1 downTo 0) {
    val nodeId = collapsedGraph.compiledGraph.getNodeId(v)
    if (isCandidateNodeId(nodeId)) {
      val downNodes = graph.getNodes(v, LiteLinearGraph.NodeFilter.DOWN)
      if (downNodes.size == 1) {
        result.add(nodeId)
        hideTrivialMerge(collapsedGraph, graph, v, downNodes.single())
      }
      else if (downNodes.size >= 2) {
        val sortedParentsIt = downNodes.sortedDescending().iterator()
        var currentParent = sortedParentsIt.next()
        while (sortedParentsIt.hasNext()) {
          val nextParent = sortedParentsIt.next()
          if (!DfsUtil.isAncestor(graph, currentParent, nextParent)) continue@outer
          currentParent = nextParent
        }
        result.add(nodeId)
        hideTrivialMerge(collapsedGraph, graph, v, currentParent)
      }
    }
  }

  return result
}

private fun hideTrivialMerge(collapsedGraph: CollapsedGraph, graph: LiteLinearGraph, node: Int, singleParent: Int) {
  collapsedGraph.modify {
    hideRow(node)
    for (upNode in graph.getNodes(node, LiteLinearGraph.NodeFilter.UP)) {
      connectRows(upNode, singleParent)
    }
  }
}

internal class FileHistoryRefiner(private val visibleLinearGraph: LinearGraph,
                                  permanentGraphInfo: PermanentGraphInfo<Int>,
                                  private val namesData: FileNamesData) : DfsUtil.NodeVisitor {
  private val permanentCommitsInfo: PermanentCommitsInfo<Int> = permanentGraphInfo.permanentCommitsInfo
  private val permanentLinearGraph: LiteLinearGraph = LinearGraphUtils.asLiteLinearGraph(permanentGraphInfo.linearGraph)

  private val paths = Stack<MaybeDeletedFilePath>()
  private val visibilityBuffer = BitSetFlags(permanentLinearGraph.nodesCount()) // a reusable buffer for bfs
  private val pathsForCommits = ContainerUtil.newHashMap<Int, MaybeDeletedFilePath>()
  private val excluded = ContainerUtil.newHashSet<Int>()

  fun refine(row: Int, startPath: MaybeDeletedFilePath): Pair<HashMap<Int, MaybeDeletedFilePath>, HashSet<Int>> {
    paths.push(startPath)
    DfsUtil.walk(LinearGraphUtils.asLiteLinearGraph(visibleLinearGraph), row, this)

    pathsForCommits.forEach { commit, path ->
      if (path != null && !namesData.affects(commit, path)) {
        excluded.add(commit)
      }
    }

    excluded.forEach { pathsForCommits.remove(it) }
    return Pair(pathsForCommits, excluded)
  }

  override fun enterNode(currentNode: Int, previousNode: Int, down: Boolean) {
    val currentNodeId = visibleLinearGraph.getNodeId(currentNode)
    val currentCommit = permanentCommitsInfo.getCommitId(currentNodeId)

    val previousPath = paths.last()
    var currentPath: MaybeDeletedFilePath = previousPath

    if (previousNode != DfsUtil.NextNode.NODE_NOT_FOUND) {
      val previousNodeId = visibleLinearGraph.getNodeId(previousNode)
      val previousCommit = permanentCommitsInfo.getCommitId(previousNodeId)

      currentPath = if (down) {
        val pathGetter = { parentIndex: Int ->
          namesData.getPathInParentRevision(previousCommit, permanentCommitsInfo.getCommitId(parentIndex), previousPath.filePath)
        }
        val path = findPathWithoutConflict(previousNodeId, pathGetter)
        path ?: pathGetter(getCorrespondingParent(permanentLinearGraph, previousNodeId, currentNodeId, visibilityBuffer))
      }
      else {
        val pathGetter = { parentIndex: Int ->
          namesData.getPathInChildRevision(currentCommit, permanentCommitsInfo.getCommitId(parentIndex), previousPath.filePath)
        }
        val path = findPathWithoutConflict(currentNodeId, pathGetter)
        // since in reality there is no edge between the nodes, but the whole path, we need to know, which parent is affected by this path
        path ?: pathGetter(getCorrespondingParent(permanentLinearGraph, currentNodeId, previousNodeId, visibilityBuffer))
      }
    }

    pathsForCommits[currentCommit] = currentPath
    paths.push(currentPath)
  }

  private fun findPathWithoutConflict(nodeId: Int, pathGetter: (Int) -> MaybeDeletedFilePath): MaybeDeletedFilePath? {
    val parents = permanentLinearGraph.getNodes(nodeId, LiteLinearGraph.NodeFilter.DOWN)
    val path = pathGetter(parents.first())
    if (parents.size == 1) return path

    if (parents.subList(1, parents.size).find { pathGetter(it) != path } != null) return null
    return path
  }

  override fun exitNode(node: Int) {
    paths.pop()
  }
}

abstract class FileNamesData(filePath: FilePath) {
  // file -> (commitId -> (parent commitId -> change kind))
  private val affectedCommits = mutableMapOf<FilePath, TIntObjectHashMap<TIntObjectHashMap<ChangeKind>>>()
  private val commitToRename = MultiMap.createSmart<UnorderedPair<Int>, Rename>()

  val isEmpty: Boolean
    get() = affectedCommits.isEmpty()
  val hasRenames: Boolean
    get() = !commitToRename.isEmpty
  val files: Set<FilePath>
    get() = affectedCommits.keys

  init {
    val newPaths = mutableSetOf(filePath)

    while (newPaths.isNotEmpty()) {
      val commits = newPaths.associate { kotlin.Pair(it, getAffectedCommits(it)) }
      affectedCommits.putAll(commits)
      newPaths.clear()

      collectAdditionsDeletions(commits) { ad ->
        if (commitToRename[ad.commits].any { rename -> ad.matches(rename) }) return@collectAdditionsDeletions
        findRename(ad.parent, ad.child, ad::matches)?.let { files ->
          val rename = Rename(files.first, files.second, ad.parent, ad.child)
          commitToRename.putValue(ad.commits, rename)
          val otherPath = rename.getOtherPath(ad)!!
          if (!affectedCommits.containsKey(otherPath)) {
            newPaths.add(otherPath)
          }
        }
      }
    }
  }

  private fun collectAdditionsDeletions(commits: Map<FilePath, TIntObjectHashMap<TIntObjectHashMap<ChangeKind>>>,
                                        action: (AdditionDeletion) -> Unit) {
    commits.forEach { path, commit, changes ->
      changes.forEachEntry { parent, change ->
        createAdditionDeletion(parent, commit, change, path)?.let { ad -> action(ad) }
        true
      }
    }
  }

  private fun createAdditionDeletion(parent: Int, commit: Int, change: ChangeKind, path: FilePath): AdditionDeletion? {
    if (parent != commit && (change == ChangeKind.ADDED || change == ChangeKind.REMOVED)) {
      return AdditionDeletion(path, commit, parent, change == ChangeKind.ADDED)
    }
    return null
  }

  fun getPathInParentRevision(commit: Int, parent: Int, childPath: FilePath): MaybeDeletedFilePath {
    val commits = UnorderedPair(commit, parent)
    val otherPath = commitToRename.get(commits).firstNotNull { rename -> rename.getOtherPath(commit, childPath) }
    if (otherPath != null) return MaybeDeletedFilePath(otherPath)

    val changes = affectedCommits[childPath]?.get(commit) ?: return MaybeDeletedFilePath(childPath)
    return MaybeDeletedFilePath(childPath, changes[parent] == ChangeKind.ADDED)
  }

  fun getPathInChildRevision(commit: Int, parent: Int, parentPath: FilePath): MaybeDeletedFilePath {
    val commits = UnorderedPair(commit, parent)
    val otherPath = commitToRename.get(commits).firstNotNull { rename -> rename.getOtherPath(parent, parentPath) }
    if (otherPath != null) return MaybeDeletedFilePath(otherPath)

    val changes = affectedCommits[parentPath]?.get(commit) ?: return MaybeDeletedFilePath(parentPath)
    return MaybeDeletedFilePath(parentPath, changes[parent] == ChangeKind.REMOVED)
  }

  fun affects(commit: Int, path: MaybeDeletedFilePath): Boolean {
    val changes = affectedCommits[path.filePath]?.get(commit) ?: return false
    if (path.deleted) return changes.containsValue(ChangeKind.REMOVED)
    return !changes.containsValue(ChangeKind.REMOVED)
  }

  fun getCommits(): Set<Int> {
    val result = mutableSetOf<Int>()
    affectedCommits.forEach { _, commit, _ ->
      result.add(commit)
    }
    return result
  }

  fun buildPathsMap(): Map<Int, MaybeDeletedFilePath> {
    val result = mutableMapOf<Int, MaybeDeletedFilePath>()
    affectedCommits.forEach { filePath, commit, changes ->
      result[commit] = MaybeDeletedFilePath(filePath, changes.containsValue(ChangeKind.REMOVED))
    }
    return result
  }

  fun isTrivialMerge(commit: Int, filePath: FilePath): Boolean {
    return affectedCommits[filePath]?.get(commit)?.let {
      it.size() > 1 && it.containsValue(ChangeKind.NOT_CHANGED)
    } ?: false
  }

  abstract fun findRename(parent: Int, child: Int, accept: (Couple<FilePath>) -> Boolean): Couple<FilePath>?
  abstract fun getAffectedCommits(path: FilePath): TIntObjectHashMap<TIntObjectHashMap<VcsLogPathsIndex.ChangeKind>>
}

private data class AdditionDeletion(val filePath: FilePath, val child: Int, val parent: Int, val isAddition: Boolean) {
  val commits
    get() = UnorderedPair(parent, child)

  fun matches(rename: Rename): Boolean {
    if (rename.commit1 == parent && rename.commit2 == child) {
      return if (isAddition) rename.filePath2 == filePath else rename.filePath1 == filePath
    }
    else if (rename.commit2 == parent && rename.commit1 == child) {
      return if (isAddition) rename.filePath1 == filePath else rename.filePath2 == filePath
    }
    return false
  }

  fun matches(files: Couple<FilePath>): Boolean {
    return (isAddition && files.second == filePath) || (!isAddition && files.first == filePath)
  }
}

private data class Rename(val filePath1: FilePath, val filePath2: FilePath, val commit1: Int, val commit2: Int) {

  fun getOtherPath(commit: Int, filePath: FilePath): FilePath? {
    if (commit == commit1 && filePath == filePath1) return filePath2
    if (commit == commit2 && filePath == filePath2) return filePath1
    return null
  }

  fun getOtherPath(ad: AdditionDeletion): FilePath? {
    return getOtherPath(if (ad.isAddition) ad.child else ad.parent, ad.filePath)
  }
}

data class MaybeDeletedFilePath(val filePath: FilePath, val deleted: Boolean) {
  constructor(filePath: FilePath) : this(filePath, false)
}

internal fun Map<FilePath, TIntObjectHashMap<TIntObjectHashMap<VcsLogPathsIndex.ChangeKind>>>.forEach(action: (FilePath, Int, TIntObjectHashMap<VcsLogPathsIndex.ChangeKind>) -> Unit) {
  forEach { (filePath, affectedCommits) ->
    affectedCommits.forEachEntry { commit, changesMap ->
      action(filePath, commit, changesMap)
      true
    }
  }
}

private fun <E, R> Collection<E>.firstNotNull(mapping: (E) -> R): R? {
  for (e in this) {
    val value = mapping(e)
    if (value != null) return value
  }
  return null
}