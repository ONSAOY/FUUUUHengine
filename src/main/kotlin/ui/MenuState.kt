package ui

import game.Difficulty
import game.GameMode
import game.Move
import game.Square

enum class ScreenState {
    MAIN_MENU,
    AI_DIFFICULTY,
    MATCH
}

class UiState {
    var screen: ScreenState = ScreenState.MAIN_MENU
    var mode: GameMode = GameMode.PVP
    var difficulty: Difficulty = Difficulty.MEDIUM

    var selectedSquare: Square? = null
    var legalMoves: List<Move> = emptyList()
    var statusText: String = "Выберите режим игры"
}
