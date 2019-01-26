package gg.rsmod.game.model.path.strategy

import gg.rsmod.game.model.Direction
import gg.rsmod.game.model.EntityType
import gg.rsmod.game.model.Tile
import gg.rsmod.game.model.World
import gg.rsmod.game.model.path.PathfindingStrategy
import org.apache.logging.log4j.LogManager
import java.util.*

/**
 * A [PathfindingStrategy] which uses breadth-first search algorithm to calculate
 * a valid path to the target location.
 *
 * @author Tom <rspsmods@gmail.com>
 */
class BFSPathfindingStrategy(override val world: World) : PathfindingStrategy(world) {

    companion object {
        private val logger = LogManager.getLogger(BFSPathfindingStrategy::class.java)
    }

    override fun calculateRoute(start: Tile, end: Tile, type: EntityType, sourceWidth: Int, sourceLength: Int,
                                targetWidth: Int, targetLength: Int, invalidBorderTile: (Tile) -> (Boolean)): Route {

        val validTiles = arrayListOf<Tile>()

        if (targetWidth > 0 || targetLength > 0) {
            for (x in -1..targetWidth) {
                for (z in -1..targetLength) {
                    val tile = end.transform(x, z)
                    if (invalidBorderTile.invoke(tile)) {
                        continue
                    }
                    validTiles.add(tile)
                }
            }
        } else {
            validTiles.add(end)
        }

        val nodes = ArrayDeque<Node>()
        val closed = hashSetOf<Node>()
        var tail: Node? = null
        var searchLimit = 256 * 10
        var success = false

        nodes.add(Node(tile = start, parent = null))

        while (nodes.isNotEmpty()) {

            if (searchLimit-- == 0) {
                logger.warn("Had to exit path early as max search samples ran out. [start=$start, end=$end, distance=${start.getDistance(end)}]")
                break
            }

            val head = nodes.poll()

            if (head.tile in validTiles) {
                tail = head
                success = true
                break
            }

            val order = Direction.RS_ORDER.sortedBy { head.tile.step(it).getDelta(end) + head.tile.step(it).getDelta(head.tile) }

            order.forEach { direction ->
                val tile = head.tile.step(direction)
                val node = Node(tile = tile, parent = head)
                if (!closed.contains(node) && head.tile.isWithinRadius(tile, MAX_DISTANCE)) {

                    var canTraverse = true
                    sourceLoop@ for (x in 0 until sourceWidth) {
                        for (z in 0 until sourceLength) {
                            if (!world.collision.canTraverse(head.tile, direction, type) || !world.collision.canTraverse(tile, direction.getOpposite(), type)) {
                                canTraverse = false
                                break@sourceLoop
                            }
                        }
                    }

                    if (canTraverse) {
                        node.cost = head.cost + 1
                        nodes.add(node)
                        closed.add(node)
                    }
                }
            }
        }

        if (tail == null && closed.isNotEmpty()) {
            val min = closed.minBy { it.tile.getDistance(end) }!!
            val valid = closed.filter { it.tile.getDistance(end) <= min.tile.getDistance(end) }
            if (valid.isNotEmpty()) {
                tail = valid.minBy { it.tile.getDelta(start) }
            }
        }

        val path = ArrayDeque<Tile>()
        while (tail?.parent != null) {
            path.addFirst(tail.tile)
            tail = tail.parent
        }

        return Route(path = path, success = success)
    }

    /**
     * A [Node] represents an single tile in a path, which can have the previous
     * tile in the path attached to it as a parent node.
     */
    private data class Node(val tile: Tile, var parent: Node?) {

        /**
         * The amount of interconnected nodes from this node to its parents,
         * and their parents, and their parents ...
         */
        var cost: Int = 0

        override fun equals(other: Any?): Boolean = (other as? Node)?.tile?.sameAs(tile) ?: false

        override fun hashCode(): Int = tile.hashCode()
    }
}