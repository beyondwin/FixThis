package io.beyondwin.fixthis.mcp.session

data class InstanceLabel(val index: Int, val total: Int)

data class InstanceGrouping(
    val labels: Map<String /* itemId */, InstanceLabel>,
    val leaderItemIds: Set<String>,
)

private data class InstanceKey(val fileLine: String, val testTag: String?)

object InstanceGroupingHelper {
    fun compute(items: List<AnnotationDto>): InstanceGrouping {
        // Exclude items that have no source candidate or no selectedNode
        val eligible = items.filter { item ->
            item.selectedNode != null && item.sourceCandidates.isNotEmpty()
        }

        val labels = mutableMapOf<String, InstanceLabel>()
        val leaderItemIds = mutableSetOf<String>()

        eligible
            .groupBy { item ->
                val node = checkNotNull(item.selectedNode) {
                    "eligible filter must exclude items without selectedNode"
                }
                InstanceKey(
                    fileLine = item.sourceCandidates.first().fileWithLine(),
                    testTag = node.testTag,
                )
            }
            .filter { (_, group) -> group.size >= 2 }
            .forEach { (_, group) ->
                val ordered = group.sortedBy { item ->
                    val node = checkNotNull(item.selectedNode) {
                        "eligible filter must exclude items without selectedNode"
                    }
                    node.path.joinToString("/")
                }
                ordered.forEachIndexed { idx, item ->
                    labels[item.itemId] = InstanceLabel(index = idx + 1, total = ordered.size)
                }
                leaderItemIds.add(ordered.first().itemId)
            }

        return InstanceGrouping(labels = labels, leaderItemIds = leaderItemIds)
    }
}
