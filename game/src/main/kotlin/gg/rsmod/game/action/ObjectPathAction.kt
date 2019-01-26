package gg.rsmod.game.action

import gg.rsmod.game.fs.def.ObjectDef
import gg.rsmod.game.model.*
import gg.rsmod.game.model.collision.ObjectType
import gg.rsmod.game.model.entity.Entity
import gg.rsmod.game.model.entity.GameObject
import gg.rsmod.game.model.entity.Pawn
import gg.rsmod.game.model.entity.Player
import gg.rsmod.game.model.path.PathfindingStrategy
import gg.rsmod.game.plugin.Plugin
import gg.rsmod.util.DataConstants

/**
 * Responsible for calculating distances and valid interaction tiles for
 * [GameObject] pathing.
 *
 * @author Tom <rspsmods@gmail.com>
 */
object ObjectPathAction {

    val walkPlugin: (Plugin) -> Unit = {
        val player = it.ctx as Player
        val obj = player.attr[INTERACTING_OBJ_ATTR]!!
        val opt = player.attr[INTERACTING_OPT_ATTR]!!

        it.suspendable {
            val route = walkTo(it, obj)
            faceObj(player, obj)
            if (route.success) {
                it.wait(1)
                handleAction(it, obj, opt)
            } else {
                player.message(Entity.YOU_CANT_REACH_THAT)
            }
        }
    }

    private suspend fun walkTo(it: Plugin, obj: GameObject): PathfindingStrategy.Route {
        val pawn = it.ctx as Pawn

        val def = obj.getDef(pawn.world.definitions)
        var tile = obj.tile
        val type = obj.type
        val rot = obj.rot
        var width = def.width
        var length = def.length

        if (rot == 1 || rot == 3) {
            width = def.length
            length = def.width
        }

        val route = pawn.createPathingStrategy().calculateRoute(
                start = pawn.tile, end = tile, sourceWidth = pawn.getSize(), sourceLength = pawn.getSize(),
                targetWidth = width, targetLength = length, type = pawn.getType(),
                invalidBorderTile = { isInvalid(it, obj, width, length) || getBlockedDirections(pawn.world, obj).contains(it) })

        pawn.walkPath(route.path, MovementQueue.StepType.NORMAL)

        val last = pawn.movementQueue.peekLast()
        while (last != null && !pawn.tile.sameAs(last)) {
            it.wait(1)
        }
        return route
    }

    private fun isInvalid(tile: Tile, obj: GameObject, width: Int, length: Int): Boolean {
        if (obj.type == ObjectType.LENGTHWISE_WALL.value) {
            val valid = when (obj.rot) {
                0 -> tile.x >= obj.tile.x - width && tile.x <= obj.tile.x && tile.z >= obj.tile.z - length && tile.z <= obj.tile.z + length
                1 -> tile.sameAs(obj.tile) || tile.sameAs(obj.tile.transform(0, 1))
                2 -> tile.sameAs(obj.tile) || tile.sameAs(obj.tile.transform(1, 0))
                3 -> tile.sameAs(obj.tile) || tile.sameAs(obj.tile.transform(0, -1)) || tile.sameAs(obj.tile.transform(-1, 0))
                else -> true
            }
            return !valid
        } else if (obj.type == ObjectType.INTERACTABLE_WALL.value || obj.type == ObjectType.INTERACTABLE_WALL_DECORATION.value) {
            return !tile.sameAs(obj.tile)
        }
        return tile.x in obj.tile.x until obj.tile.x + width && tile.z in obj.tile.z until obj.tile.z + length
    }

    private fun handleAction(it: Plugin, obj: GameObject, opt: Int) {
        val p = it.ctx as Player

        if (!p.world.plugins.executeObject(p, obj.id, opt)) {
            p.message(Entity.NOTHING_INTERESTING_HAPPENS)
            if (p.world.devContext.debugObjects) {
                p.message("Unhandled object action: [opt=$opt, id=${obj.id}, type=${obj.type}, rot=${obj.rot}, x=${obj.tile.x}, z=${obj.tile.z}]")
            }
        }
    }

    private fun faceObj(pawn: Pawn, obj: GameObject) {
        val def = pawn.world.definitions.get(ObjectDef::class.java, obj.id)
        val rot = obj.rot
        val type = obj.type

        var width = def.width
        var length = def.length

        if (rot == 1 || rot == 3) {
            width = def.length
            length = def.width
        }

        when (type) {
            ObjectType.LENGTHWISE_WALL.value -> {
                /**
                 * Specially logic for facing doors and walls, otherwise you
                 * end up facing the same direction the object is facing.
                 */
                val dir = when (rot) {
                    0 -> obj.tile.transform(if (pawn.tile.x == obj.tile.x) -1 else 0, 0)
                    1 -> obj.tile.transform(0, 1)
                    2 -> obj.tile.transform(1, 0)
                    else -> obj.tile.transform(0, -1)
                }
                pawn.faceTile(dir)
            }
            else -> {
                pawn.faceTile(obj.tile.transform(width shr 1, length shr 1), width, length)
            }
        }
    }

    private fun getBlockedDirections(world: World, obj: GameObject): Set<Tile> {
        val def = world.definitions.get(ObjectDef::class.java, obj.id)

        val blockBits = 4
        val clipMask = def.clipFlag
        val clipFlag = (DataConstants.BIT_MASK[blockBits] and (clipMask shl obj.rot)) or (clipMask shr (blockBits - obj.rot))

        val tile = obj.tile
        val type = obj.type
        val rot = obj.rot
        var width = def.width
        var length = def.length

        if (rot == 1 || rot == 3) {
            width = def.length
            length = def.width
        }

        val tiles = hashSetOf<Tile>()

        val blockNorth = (clipFlag and 0x1) != 0
        val blockEast = (clipFlag and 0x2) != 0
        val blockSouth = (clipFlag and 0x4) != 0
        val blockWest = (clipFlag and 0x8) != 0

        if (blockNorth) {
            for (x in 0 until width) {
                tiles.add(tile.transform(x, length))
            }
        }

        if (blockEast) {
            for (z in 0 until length) {
                tiles.add(tile.transform(width, z))
            }
        }

        if (blockSouth) {
            for (x in 0 until width) {
                tiles.add(tile.transform(x, -1))
            }
        }

        if (blockWest) {
            for (z in 0 until length) {
                tiles.add(tile.transform(-1, z))
            }
        }

        if (type == ObjectType.DIAGONAL_INTERACTABLE.value || type == ObjectType.INTERACTABLE.value) {
            for (x in -1..width) {
                loop@ for (z in -1..length) {
                    if (x in 0 until width && z in 0 until length) {
                        continue
                    }
                    val transform = tile.transform(x, z)
                    val face = when {
                        (x == -1 && z != -1 && z != length) -> Direction.EAST
                        (x == width && z != -1 && z != length) -> Direction.WEST
                        (z == -1 && x != -1 && x != width) -> Direction.NORTH
                        (z == length && x != -1 && x != width) -> Direction.SOUTH
                        (x == -1 && z == -1) -> Direction.SOUTH_WEST
                        (x == -1 && z == length) -> Direction.NORTH_WEST
                        (z == -1 && x == width) -> Direction.SOUTH_EAST
                        (z == length && x == width) -> Direction.NORTH_EAST
                        else -> continue@loop
                    }
                    if (face.isDiagonal() || world.collision.isBlocked(transform, face, projectile = false)) {
                        tiles.add(transform)
                    }
                }
            }
        }

        return tiles
    }
}