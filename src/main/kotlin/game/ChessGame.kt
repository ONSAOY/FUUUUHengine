package game

enum class PieceColor { WHITE, BLACK;
    fun opposite(): PieceColor = if (this == WHITE) BLACK else WHITE
}

enum class PieceType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

data class Piece(val type: PieceType, val color: PieceColor)

data class Square(val file: Int, val rank: Int) {
    fun inside(): Boolean = file in 0..7 && rank in 0..7
}

enum class GameResult {
    ONGOING,
    WHITE_WIN,
    BLACK_WIN,
    DRAW_STALEMATE,
    DRAW_INSUFFICIENT_MATERIAL
}

enum class GameMode { PVP, VS_AI }

enum class Difficulty(val depth: Int) {
    EASY(2),
    MEDIUM(4),
    HARD(5)
}

data class CastlingRights(
    val whiteKingSide: Boolean = true,
    val whiteQueenSide: Boolean = true,
    val blackKingSide: Boolean = true,
    val blackQueenSide: Boolean = true
)

data class Move(
    val from: Square,
    val to: Square,
    val promotion: PieceType? = null,
    val isEnPassant: Boolean = false,
    val isCastling: Boolean = false
)

data class MoveRecord(
    val move: Move,
    val moved: Piece,
    val captured: Piece?,
    val capturedSquare: Square?,
    val previousEnPassantTarget: Square?,
    val previousCastlingRights: CastlingRights,
    val previousHalfMoveClock: Int
)

class ChessGame {
    private val board: Array<Array<Piece?>> = Array(8) { Array<Piece?>(8) { null } }
    private val history = mutableListOf<MoveRecord>()

    var sideToMove: PieceColor = PieceColor.WHITE
        private set
    var result: GameResult = GameResult.ONGOING
        private set
    var winner: PieceColor? = null
        private set
    var enPassantTarget: Square? = null
        private set
    var castlingRights: CastlingRights = CastlingRights()
        private set
    var halfMoveClock: Int = 0
        private set

    init {
        reset()
    }

    fun reset() {
        for (r in 0..7) for (f in 0..7) board[r][f] = null
        history.clear()
        sideToMove = PieceColor.WHITE
        result = GameResult.ONGOING
        winner = null
        enPassantTarget = null
        castlingRights = CastlingRights()
        halfMoveClock = 0
        setupInitialPosition()
        evaluateGameState()
    }

    fun pieceAt(square: Square): Piece? = if (square.inside()) board[square.rank][square.file] else null

    fun allPieces(): List<Pair<Square, Piece>> {
        val result = mutableListOf<Pair<Square, Piece>>()
        for (r in 0..7) {
            for (f in 0..7) {
                val p = board[r][f]
                if (p != null) result += Square(f, r) to p
            }
        }
        return result
    }

    fun legalMovesFrom(square: Square): List<Move> {
        val piece = pieceAt(square) ?: return emptyList()
        if (piece.color != sideToMove || result != GameResult.ONGOING) return emptyList()
        return pseudoLegalMovesFrom(square, piece).filter { move ->
            val record = applyMoveUnchecked(move)
            val legal = !isKingInCheck(piece.color)
            undoMove(record)
            legal
        }
    }

    fun legalMoves(): List<Move> {
        val out = mutableListOf<Move>()
        for ((sq, piece) in allPieces()) {
            if (piece.color == sideToMove) {
                out += legalMovesFrom(sq)
            }
        }
        return out
    }

    fun tryMakeMove(move: Move): Boolean {
        val legal = legalMovesFrom(move.from).firstOrNull {
            it.to == move.to && (it.promotion == move.promotion || it.promotion == null || move.promotion == null)
        } ?: return false
        val normalized = if (legal.promotion != null && move.promotion == null) {
            legal.copy(promotion = PieceType.QUEEN)
        } else if (legal.promotion != null && move.promotion != null) {
            legal.copy(promotion = move.promotion)
        } else {
            legal
        }
        val record = applyMoveUnchecked(normalized)
        history += record
        sideToMove = sideToMove.opposite()
        evaluateGameState()
        return true
    }

    fun makeMoveUncheckedForSearch(move: Move): MoveRecord = applyMoveUnchecked(move)

    fun undoSearchMove(record: MoveRecord) = undoMove(record)

    fun undoLastMoveForSearch(): Boolean {
        val last = history.removeLastOrNull() ?: return false
        sideToMove = sideToMove.opposite()
        undoMove(last)
        evaluateGameState()
        return true
    }

    fun kingSquare(color: PieceColor): Square? {
        for (r in 0..7) for (f in 0..7) {
            val p = board[r][f]
            if (p?.type == PieceType.KING && p.color == color) return Square(f, r)
        }
        return null
    }

    fun isKingInCheck(color: PieceColor): Boolean {
        val king = kingSquare(color) ?: return false
        return isSquareAttacked(king, color.opposite())
    }

    private fun evaluateGameState() {
        if (isInsufficientMaterial()) {
            result = GameResult.DRAW_INSUFFICIENT_MATERIAL
            winner = null
            return
        }
        val moves = legalMoves()
        if (moves.isEmpty()) {
            if (isKingInCheck(sideToMove)) {
                winner = sideToMove.opposite()
                result = if (winner == PieceColor.WHITE) GameResult.WHITE_WIN else GameResult.BLACK_WIN
            } else {
                winner = null
                result = GameResult.DRAW_STALEMATE
            }
            return
        }
        winner = null
        result = GameResult.ONGOING
    }

    private fun setupInitialPosition() {
        fun put(file: Int, rank: Int, type: PieceType, color: PieceColor) {
            board[rank][file] = Piece(type, color)
        }

        for (f in 0..7) {
            put(f, 1, PieceType.PAWN, PieceColor.WHITE)
            put(f, 6, PieceType.PAWN, PieceColor.BLACK)
        }

        val back = listOf(
            PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.QUEEN,
            PieceType.KING, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK
        )
        for (f in 0..7) {
            put(f, 0, back[f], PieceColor.WHITE)
            put(f, 7, back[f], PieceColor.BLACK)
        }
    }

    private fun pseudoLegalMovesFrom(from: Square, piece: Piece): List<Move> {
        return when (piece.type) {
            PieceType.PAWN -> pawnMoves(from, piece.color)
            PieceType.KNIGHT -> knightMoves(from, piece.color)
            PieceType.BISHOP -> slidingMoves(from, piece.color, listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1))
            PieceType.ROOK -> slidingMoves(from, piece.color, listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1))
            PieceType.QUEEN -> slidingMoves(
                from, piece.color,
                listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1, 1 to 0, -1 to 0, 0 to 1, 0 to -1)
            )
            PieceType.KING -> kingMoves(from, piece.color)
        }
    }

    private fun pawnMoves(from: Square, color: PieceColor): List<Move> {
        val dir = if (color == PieceColor.WHITE) 1 else -1
        val startRank = if (color == PieceColor.WHITE) 1 else 6
        val promoRank = if (color == PieceColor.WHITE) 7 else 0
        val out = mutableListOf<Move>()

        val one = Square(from.file, from.rank + dir)
        if (one.inside() && pieceAt(one) == null) {
            if (one.rank == promoRank) {
                listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT).forEach {
                    out += Move(from, one, promotion = it)
                }
            } else {
                out += Move(from, one)
            }
            if (from.rank == startRank) {
                val two = Square(from.file, from.rank + 2 * dir)
                if (pieceAt(two) == null) out += Move(from, two)
            }
        }

        for (df in listOf(-1, 1)) {
            val to = Square(from.file + df, from.rank + dir)
            if (!to.inside()) continue
            val target = pieceAt(to)
            if (target != null && target.color != color) {
                if (to.rank == promoRank) {
                    listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT).forEach {
                        out += Move(from, to, promotion = it)
                    }
                } else {
                    out += Move(from, to)
                }
            }
            if (to == enPassantTarget) out += Move(from, to, isEnPassant = true)
        }
        return out
    }

    private fun knightMoves(from: Square, color: PieceColor): List<Move> {
        val out = mutableListOf<Move>()
        val shifts = listOf(
            1 to 2, 2 to 1, -1 to 2, -2 to 1,
            1 to -2, 2 to -1, -1 to -2, -2 to -1
        )
        for ((df, dr) in shifts) {
            val to = Square(from.file + df, from.rank + dr)
            if (!to.inside()) continue
            val target = pieceAt(to)
            if (target == null || target.color != color) out += Move(from, to)
        }
        return out
    }

    private fun slidingMoves(from: Square, color: PieceColor, dirs: List<Pair<Int, Int>>): List<Move> {
        val out = mutableListOf<Move>()
        for ((df, dr) in dirs) {
            var f = from.file + df
            var r = from.rank + dr
            while (f in 0..7 && r in 0..7) {
                val to = Square(f, r)
                val target = pieceAt(to)
                if (target == null) {
                    out += Move(from, to)
                } else {
                    if (target.color != color) out += Move(from, to)
                    break
                }
                f += df
                r += dr
            }
        }
        return out
    }

    private fun kingMoves(from: Square, color: PieceColor): List<Move> {
        val out = mutableListOf<Move>()
        for (df in -1..1) for (dr in -1..1) {
            if (df == 0 && dr == 0) continue
            val to = Square(from.file + df, from.rank + dr)
            if (!to.inside()) continue
            val target = pieceAt(to)
            if (target == null || target.color != color) out += Move(from, to)
        }

        val homeRank = if (color == PieceColor.WHITE) 0 else 7
        val kingStart = Square(4, homeRank)
        if (from == kingStart && !isKingInCheck(color)) {
            val rights = castlingRights
            val kSideAllowed = if (color == PieceColor.WHITE) rights.whiteKingSide else rights.blackKingSide
            if (kSideAllowed &&
                pieceAt(Square(5, homeRank)) == null &&
                pieceAt(Square(6, homeRank)) == null &&
                !isSquareAttacked(Square(5, homeRank), color.opposite()) &&
                !isSquareAttacked(Square(6, homeRank), color.opposite())
            ) out += Move(from, Square(6, homeRank), isCastling = true)

            val qSideAllowed = if (color == PieceColor.WHITE) rights.whiteQueenSide else rights.blackQueenSide
            if (qSideAllowed &&
                pieceAt(Square(3, homeRank)) == null &&
                pieceAt(Square(2, homeRank)) == null &&
                pieceAt(Square(1, homeRank)) == null &&
                !isSquareAttacked(Square(3, homeRank), color.opposite()) &&
                !isSquareAttacked(Square(2, homeRank), color.opposite())
            ) out += Move(from, Square(2, homeRank), isCastling = true)
        }
        return out
    }

    private fun isSquareAttacked(square: Square, byColor: PieceColor): Boolean {
        for ((from, piece) in allPieces()) {
            if (piece.color != byColor) continue
            when (piece.type) {
                PieceType.PAWN -> {
                    val dir = if (byColor == PieceColor.WHITE) 1 else -1
                    if (Square(from.file - 1, from.rank + dir) == square) return true
                    if (Square(from.file + 1, from.rank + dir) == square) return true
                }
                PieceType.KNIGHT -> {
                    if (knightMoves(from, byColor).any { it.to == square }) return true
                }
                PieceType.BISHOP -> {
                    if (slidingMoves(from, byColor, listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)).any { it.to == square }) return true
                }
                PieceType.ROOK -> {
                    if (slidingMoves(from, byColor, listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)).any { it.to == square }) return true
                }
                PieceType.QUEEN -> {
                    if (slidingMoves(from, byColor, listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1, 1 to 0, -1 to 0, 0 to 1, 0 to -1)).any { it.to == square }) return true
                }
                PieceType.KING -> {
                    if (kotlin.math.abs(from.file - square.file) <= 1 && kotlin.math.abs(from.rank - square.rank) <= 1) return true
                }
            }
        }
        return false
    }

    private fun applyMoveUnchecked(move: Move): MoveRecord {
        val moved = pieceAt(move.from) ?: error("No piece on from-square")
        val prevEp = enPassantTarget
        val prevRights = castlingRights
        val prevHalfMove = halfMoveClock

        board[move.from.rank][move.from.file] = null
        var capturedSquare: Square? = move.to
        var captured = pieceAt(move.to)

        if (move.isEnPassant) {
            val capRank = if (moved.color == PieceColor.WHITE) move.to.rank - 1 else move.to.rank + 1
            val epCapSquare = Square(move.to.file, capRank)
            capturedSquare = epCapSquare
            captured = pieceAt(epCapSquare)
            board[epCapSquare.rank][epCapSquare.file] = null
        }

        val placed = if (move.promotion != null) Piece(move.promotion, moved.color) else moved
        board[move.to.rank][move.to.file] = placed

        if (move.isCastling && moved.type == PieceType.KING) {
            val home = if (moved.color == PieceColor.WHITE) 0 else 7
            if (move.to.file == 6) {
                val rookFrom = Square(7, home)
                val rookTo = Square(5, home)
                board[rookTo.rank][rookTo.file] = board[rookFrom.rank][rookFrom.file]
                board[rookFrom.rank][rookFrom.file] = null
            } else if (move.to.file == 2) {
                val rookFrom = Square(0, home)
                val rookTo = Square(3, home)
                board[rookTo.rank][rookTo.file] = board[rookFrom.rank][rookFrom.file]
                board[rookFrom.rank][rookFrom.file] = null
            }
        }

        enPassantTarget = null
        if (moved.type == PieceType.PAWN && kotlin.math.abs(move.to.rank - move.from.rank) == 2) {
            enPassantTarget = Square(move.from.file, (move.to.rank + move.from.rank) / 2)
        }

        castlingRights = updateCastlingRightsAfterMove(moved, move, capturedSquare)
        halfMoveClock = if (moved.type == PieceType.PAWN || captured != null) 0 else halfMoveClock + 1

        return MoveRecord(
            move = move,
            moved = moved,
            captured = captured,
            capturedSquare = capturedSquare,
            previousEnPassantTarget = prevEp,
            previousCastlingRights = prevRights,
            previousHalfMoveClock = prevHalfMove
        )
    }

    private fun updateCastlingRightsAfterMove(moved: Piece, move: Move, capturedSquare: Square?): CastlingRights {
        var rights = castlingRights
        if (moved.type == PieceType.KING) {
            rights = if (moved.color == PieceColor.WHITE) {
                rights.copy(whiteKingSide = false, whiteQueenSide = false)
            } else {
                rights.copy(blackKingSide = false, blackQueenSide = false)
            }
        }
        if (moved.type == PieceType.ROOK) {
            rights = when (move.from) {
                Square(0, 0) -> rights.copy(whiteQueenSide = false)
                Square(7, 0) -> rights.copy(whiteKingSide = false)
                Square(0, 7) -> rights.copy(blackQueenSide = false)
                Square(7, 7) -> rights.copy(blackKingSide = false)
                else -> rights
            }
        }
        if (capturedSquare != null) {
            rights = when (capturedSquare) {
                Square(0, 0) -> rights.copy(whiteQueenSide = false)
                Square(7, 0) -> rights.copy(whiteKingSide = false)
                Square(0, 7) -> rights.copy(blackQueenSide = false)
                Square(7, 7) -> rights.copy(blackKingSide = false)
                else -> rights
            }
        }
        return rights
    }

    private fun undoMove(record: MoveRecord) {
        val move = record.move
        enPassantTarget = record.previousEnPassantTarget
        castlingRights = record.previousCastlingRights
        halfMoveClock = record.previousHalfMoveClock

        board[move.to.rank][move.to.file] = null
        board[move.from.rank][move.from.file] = record.moved

        if (move.isCastling && record.moved.type == PieceType.KING) {
            val home = if (record.moved.color == PieceColor.WHITE) 0 else 7
            if (move.to.file == 6) {
                board[home][7] = board[home][5]
                board[home][5] = null
            } else if (move.to.file == 2) {
                board[home][0] = board[home][3]
                board[home][3] = null
            }
        }

        if (record.captured != null && record.capturedSquare != null) {
            board[record.capturedSquare.rank][record.capturedSquare.file] = record.captured
        }
    }

    private fun isInsufficientMaterial(): Boolean {
        val pieces = allPieces().map { it.second }
        if (pieces.any { it.type == PieceType.PAWN || it.type == PieceType.ROOK || it.type == PieceType.QUEEN }) return false
        val whiteMinors = pieces.filter { it.color == PieceColor.WHITE && (it.type == PieceType.BISHOP || it.type == PieceType.KNIGHT) }
        val blackMinors = pieces.filter { it.color == PieceColor.BLACK && (it.type == PieceType.BISHOP || it.type == PieceType.KNIGHT) }
        return whiteMinors.size <= 1 && blackMinors.size <= 1
    }
}
