package com.padelsync.app

import com.netsports.core.engine.ServeSide
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team

/**
 * A court drawn from above with the net running across the middle, in
 * fractions of the space available: (0, 0) is the top-left corner, (1, 1)
 * the bottom-right. Team A plays in the top half and team B in the bottom.
 *
 * Pure numbers, no drawing: [CourtBackground] turns them into pixels.
 */
class CourtGeometry private constructor(
    /** The playing area. In padel it is also where the walls stand. */
    val bounds: Area,
    /** Lines painted on the court, the outline excluded. */
    val lines: List<Line>,
    /** The four boxes a serve must land in. */
    private val boxTopLeft: Area,
    private val boxTopRight: Area,
    private val boxBottomLeft: Area,
    private val boxBottomRight: Area,
) {
    /** A rectangle, as fractions of the canvas. */
    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** A straight line, as fractions of the canvas. */
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    val netY: Float
        get() = 0.5f

    /**
     * The box a serve from [server] on [side] must land in: always the one
     * diagonally opposite.
     */
    fun targetBox(server: Team, side: ServeSide): Area {
        val fromScreenLeft = serverOnScreenLeft(server, side)
        return when (server) {
            Team.A -> if (fromScreenLeft) boxBottomRight else boxBottomLeft
            Team.B -> if (fromScreenLeft) boxTopRight else boxTopLeft
        }
    }

    companion object {
        /**
         * Whether a server on [side] stands on the left of the screen.
         *
         * Sides are named from the server's own point of view. Team A, at
         * the top, faces down the screen, so its right is the viewer's left.
         */
        fun serverOnScreenLeft(server: Team, side: ServeSide): Boolean =
            (server == Team.A) == (side == ServeSide.RIGHT)

        fun of(sport: Sport): CourtGeometry = if (sport == Sport.PADEL) padel() else tennis()

        /**
         * Padel: 20 m by 10 m inside the walls, service lines 6.95 m from
         * the net, and a centre line between them.
         */
        private fun padel(): CourtGeometry {
            val bounds = Area(0.03f, 0.015f, 0.97f, 0.985f)
            val halfLength = (bounds.bottom - bounds.top) / 2
            val service = halfLength * (6.95f / 10f)
            val topService = 0.5f - service
            val bottomService = 0.5f + service
            return CourtGeometry(
                bounds = bounds,
                lines = listOf(
                    Line(bounds.left, topService, bounds.right, topService),
                    Line(bounds.left, bottomService, bounds.right, bottomService),
                    Line(0.5f, topService, 0.5f, bottomService),
                ),
                boxTopLeft = Area(bounds.left, topService, 0.5f, 0.5f),
                boxTopRight = Area(0.5f, topService, bounds.right, 0.5f),
                boxBottomLeft = Area(bounds.left, 0.5f, 0.5f, bottomService),
                boxBottomRight = Area(0.5f, 0.5f, bounds.right, bottomService),
            )
        }

        /**
         * Tennis: 23.77 m by 10.97 m with the doubles alleys, singles lines
         * 1.37 m in, service lines 6.40 m from the net, and room to run
         * around the outside.
         */
        private fun tennis(): CourtGeometry {
            val bounds = Area(0.09f, 0.07f, 0.91f, 0.93f)
            val width = bounds.right - bounds.left
            val halfLength = (bounds.bottom - bounds.top) / 2
            val alley = width * (1.37f / 10.97f)
            val singlesLeft = bounds.left + alley
            val singlesRight = bounds.right - alley
            val service = halfLength * (6.40f / 11.885f)
            val topService = 0.5f - service
            val bottomService = 0.5f + service
            val mark = 0.012f
            return CourtGeometry(
                bounds = bounds,
                lines = listOf(
                    Line(singlesLeft, bounds.top, singlesLeft, bounds.bottom),
                    Line(singlesRight, bounds.top, singlesRight, bounds.bottom),
                    Line(singlesLeft, topService, singlesRight, topService),
                    Line(singlesLeft, bottomService, singlesRight, bottomService),
                    Line(0.5f, topService, 0.5f, bottomService),
                    // Centre marks on the baselines.
                    Line(0.5f, bounds.top, 0.5f, bounds.top + mark),
                    Line(0.5f, bounds.bottom - mark, 0.5f, bounds.bottom),
                ),
                boxTopLeft = Area(singlesLeft, topService, 0.5f, 0.5f),
                boxTopRight = Area(0.5f, topService, singlesRight, 0.5f),
                boxBottomLeft = Area(singlesLeft, 0.5f, 0.5f, bottomService),
                boxBottomRight = Area(0.5f, 0.5f, singlesRight, bottomService),
            )
        }
    }
}
