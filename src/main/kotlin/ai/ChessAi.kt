package ai

import game.ChessGame
import game.Difficulty
import game.Move
import game.PieceColor
import game.PieceType
import game.GameResult

class ChessAi {
    fun chooseMove(game: ChessGame, difficulty: Difficulty): Move? {
        val moves = game.legalMoves()
        if (moves.isEmpty()) return null
        val maximizing = game.sideToMove == PieceColor.WHITE
        var bestMove: Move? = null
        var bestScore = if (maximizing) Int.MIN_VALUE else Int.MAX_VALUE
        var alpha = Int.MIN_VALUE
        var beta = Int.MAX_VALUE

        for (move in moves) {
            if (!game.tryMakeMove(move)) continue
            val score = minimax(game, difficulty.depth - 1, alpha, beta, !maximizing)
            game.undoLastMoveForSearch()
            if (maximizing && score > bestScore) {
                bestScore = score
                bestMove = move
            } else if (!maximizing && score < bestScore) {
                bestScore = score
                bestMove = move
            }
            if (maximizing) alpha = maxOf(alpha, bestScore) else beta = minOf(beta, bestScore)
            if (beta <= alpha) break
        }
        return bestMove ?: moves.random()
    }

    private fun minimax(
        game: ChessGame,
        depth: Int,
        alphaIn: Int,
        betaIn: Int,
        maximizing: Boolean
    ): Int {
        if (depth <= 0 || game.result != GameResult.ONGOING) return evaluate(game)
        val moves = game.legalMoves()
        if (moves.isEmpty()) return evaluate(game)

        var alpha = alphaIn
        var beta = betaIn
        if (maximizing) {
            var value = Int.MIN_VALUE
            for (move in moves) {
                if (!game.tryMakeMove(move)) continue
                value = maxOf(value, minimax(game, depth - 1, alpha, beta, false))
                game.undoLastMoveForSearch()
                alpha = maxOf(alpha, value)
                if (beta <= alpha) break
            }
            return value
        } else {
            var value = Int.MAX_VALUE
            for (move in moves) {
                if (!game.tryMakeMove(move)) continue
                value = minOf(value, minimax(game, depth - 1, alpha, beta, true))
                game.undoLastMoveForSearch()
                beta = minOf(beta, value)
                if (beta <= alpha) break
            }
            return value
        }
    }

    private fun evaluate(game: ChessGame): Int {
        val material = game.allPieces().sumOf { (_, piece) ->
            val value = when (piece.type) {
                PieceType.PAWN -> 100
                PieceType.KNIGHT -> 320
                PieceType.BISHOP -> 330
                PieceType.ROOK -> 500
                PieceType.QUEEN -> 900
                PieceType.KING -> 20_000
            }
            if (piece.color == PieceColor.WHITE) value else -value
        }

        val mobility = game.legalMoves().size * if (game.sideToMove == PieceColor.WHITE) 2 else -2
        return material + mobility
    }
}
