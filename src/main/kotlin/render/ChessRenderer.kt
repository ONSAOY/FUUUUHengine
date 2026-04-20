package render

import ai.ChessAi
import de.fabmax.kool.Assets
import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.loadTexture2d
import de.fabmax.kool.input.PointerInput
import de.fabmax.kool.math.RayTest
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.modules.gltf.loadGltfModel
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.modules.ui2.Image
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.scene.*
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import game.*
import ui.ScreenState
import ui.UiState
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.sin
import java.io.File

class ChessRenderer {
    private val game = ChessGame()
    private val ai = ChessAi()
    private val uiState = UiState()

    private val squareMeshes = mutableMapOf<Square, Mesh<*>>()
    private val meshToSquare = mutableMapOf<Mesh<*>, Square>()
    private val pieces = mutableMapOf<Square, PieceVisual>()
    private val pendingAnimations = mutableListOf<MoveAnim>()
    private val rayTest = RayTest()
    private val iconTextures = mutableMapOf<String, Texture2d>()
    private val pathMarkers = mutableListOf<Node>()
    private var selectionGlow: Node? = null
    private val minimapMoveTraces = mutableListOf<MoveTrace>()

    private val screen = mutableStateOf(ScreenState.MAIN_MENU)
    private val infoText = mutableStateOf("Выберите режим игры")
    private val selectedText = mutableStateOf("Выбрана фигура: -")
    private val legalText = mutableStateOf("Доступные ходы: -")
    private val miniBoardText = mutableStateOf("Загрузка...")
    private val modelText = mutableStateOf("")

    private var boardScene: Scene? = null

    private data class PieceVisual(
        val node: Node,
        val type: PieceType,
        val color: PieceColor,
        var square: Square,
        var worldX: Float,
        var worldZ: Float,
        var boardFx: Float,
        var boardFz: Float
    )

    private data class MoveAnim(
        val visual: PieceVisual,
        val fromX: Float,
        val fromZ: Float,
        val toX: Float,
        val toZ: Float,
        var elapsed: Float = 0f,
        val duration: Float = 0.5f,
        val baseY: Float = 0.16f
    )

    private data class MoveTrace(
        val fromFile: Float,
        val fromRank: Float,
        val toFile: Float,
        val toRank: Float,
        var age: Float = 0f,
        val lifeTime: Float = 2.2f
    )

    fun run() = KoolApplication {
        addScene {
            boardScene = this
            defaultOrbitCamera()
            modelText.value = setupFigureSourceMessage()

            buildBoardMeshes()
            rebuildAllPieces()
            createSelectionGlow()
            load2dPieceIcons()

            lighting.singleDirectionalLight {
                setup(Vec3f(-1f, -1f, -1f))
                setColor(Color.WHITE, 5f)
            }

            onUpdate {
                process3dPicking()
                runAnimations()
                runAiIfNeeded()
                updateMiniBoardText()
                animateSelectedPulse()
            }
        }

        // Отдельный ортографический UI-проход после 3D сцены.
        addScene {
            setupUiScene(ClearColorLoad)

            // Оверлей: строго плоская top-down 2D доска в стиле chess.com (только отображение).
            addPanelSurface {
                modifier
                    .size(340.dp, 390.dp)
                    .align(AlignmentX.Start, AlignmentY.Top)
                    .padding(10.dp)
                    .background(RoundRectBackground(Color(0f, 0f, 0f, 0.55f), 10.dp))

                Column {
                    Text("2D board (top-down, read-only)") {}
                    Text("Интерактивность отключена") { modifier.font(sizes.smallText) }
                    Column {
                        modifier.margin(top = 8.dp)
                        draw2dBoardPreview()
                    }
                }
            }

            addPanelSurface {
                modifier
                    .size(460.dp, 320.dp)
                    .align(AlignmentX.End, AlignmentY.Top)
                    .padding(14.dp)
                    .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 12.dp))

                Column {
                    Text("Шахматы 3D (Kotlin + Kool/OpenGL)") {}
                    Text("Камера: drag мышью + колесо") {}
                    Text(modelText.use()) { modifier.font(sizes.smallText) }
                    Text(infoText.use()) { modifier.margin(top = sizes.gap) }
                    Text(selectedText.use()) { modifier.font(sizes.smallText) }
                    Text(legalText.use()) { modifier.font(sizes.smallText) }
                    Text("Интерактивность: только клики по 3D-доске") { modifier.font(sizes.smallText) }

                    if (screen.use() == ScreenState.MAIN_MENU) {
                        Button("Игрок против ИИ") {
                            modifier.margin(top = sizes.gap).onClick {
                                uiState.mode = GameMode.VS_AI
                                screen.value = ScreenState.AI_DIFFICULTY
                                uiState.screen = ScreenState.AI_DIFFICULTY
                                infoText.value = "Выберите сложность"
                            }
                        }
                        Button("Два игрока") {
                            modifier.margin(top = sizes.smallGap).onClick {
                                uiState.mode = GameMode.PVP
                                uiState.screen = ScreenState.MATCH
                                screen.value = ScreenState.MATCH
                                restart()
                            }
                        }
                    } else if (screen.use() == ScreenState.AI_DIFFICULTY) {
                        Row {
                            Button("Легко") { modifier.onClick { startAi(Difficulty.EASY) } }
                            Button("Средне") { modifier.margin(start = 6.dp).onClick { startAi(Difficulty.MEDIUM) } }
                            Button("Сложно") { modifier.margin(start = 6.dp).onClick { startAi(Difficulty.HARD) } }
                        }
                        Button("Назад") {
                            modifier.margin(top = sizes.gap).onClick {
                                uiState.screen = ScreenState.MAIN_MENU
                                screen.value = ScreenState.MAIN_MENU
                            }
                        }
                    } else {
                        Text("Режим матча: кликайте по 3D-клеткам") { modifier.margin(top = sizes.gap) }
                        Button("Новая партия") {
                            modifier.margin(top = sizes.gap).onClick { restart() }
                        }
                        Button("В меню") {
                            modifier.margin(top = sizes.smallGap).onClick {
                                uiState.screen = ScreenState.MAIN_MENU
                                screen.value = ScreenState.MAIN_MENU
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startAi(difficulty: Difficulty) {
        uiState.difficulty = difficulty
        uiState.mode = GameMode.VS_AI
        uiState.screen = ScreenState.MATCH
        screen.value = ScreenState.MATCH
        restart()
    }

    private fun restart() {
        game.reset()
        uiState.selectedSquare = null
        uiState.legalMoves = emptyList()
        selectedText.value = "Выбрана фигура: -"
        legalText.value = "Доступные ходы: -"
        infoText.value = "Новая игра. Ход белых"
        clearPieceNodes()
        rebuildAllPieces()
        clearPathMarkers()
        selectionGlow?.isVisible = false
        minimapMoveTraces.clear()
    }

    private fun handleSquareClick(square: Square) {
        if (game.result != GameResult.ONGOING) return
        val selected = uiState.selectedSquare

        if (selected == null) {
            val piece = game.pieceAt(square)
            if (piece != null && piece.color == game.sideToMove) {
                val moves = game.legalMovesFrom(square)
                uiState.selectedSquare = square
                uiState.legalMoves = moves
                selectedText.value = "Выбрана: ${fmt(square)} ${piece.type}"
                legalText.value = "Ходы: ${moves.joinToString { fmt(it.to) }}"
                updateSelectionVisuals()
            }
            return
        }

        val move = uiState.legalMoves.firstOrNull { it.to == square }
        if (move != null) {
            applyMove(move)
            uiState.selectedSquare = null
            uiState.legalMoves = emptyList()
            selectedText.value = "Выбрана фигура: -"
            legalText.value = "Доступные ходы: -"
            clearPathMarkers()
            selectionGlow?.isVisible = false
            infoText.value = when (game.result) {
                GameResult.ONGOING -> "Ход: ${if (game.sideToMove == PieceColor.WHITE) "белых" else "черных"}"
                GameResult.WHITE_WIN -> "Мат. Победа белых"
                GameResult.BLACK_WIN -> "Мат. Победа черных"
                GameResult.DRAW_STALEMATE -> "Пат"
                GameResult.DRAW_INSUFFICIENT_MATERIAL -> "Ничья"
            }
            return
        }

        uiState.selectedSquare = null
        uiState.legalMoves = emptyList()
        selectedText.value = "Выбрана фигура: -"
        legalText.value = "Доступные ходы: -"
        clearPathMarkers()
        selectionGlow?.isVisible = false
    }

    private fun buildBoardMeshes() {
        for (x in 0..7) for (z in 0..7) {
            val square = Square(x, z)
            val light = (x + z) % 2 == 0
            val color = if (light) Color(0.9f, 0.8f, 0.65f, 1f) else Color(0.25f, 0.16f, 0.08f, 1f)
            val mesh = boardScene!!.addColorMesh {
                generate { cube { colored() } }
                shader = KslPbrShader {
                    color { constColor(color) }
                    metallic(0f)
                    roughness(0.5f)
                }
                transform.scale(Vec3f(0.48f, 0.04f, 0.48f))
                transform.translate(worldX(x), -0.1f, worldZ(z))
            }
            mesh.name = "board_${x}_${z}"
            mesh.isPickable = true
            squareMeshes[square] = mesh
            meshToSquare[mesh] = square
        }
    }

    private fun createSelectionGlow() {
        val glow = boardScene!!.addColorMesh {
            generate { cube { colored() } }
            shader = KslPbrShader {
                color { constColor(Color(1f, 0.9f, 0.2f, 0.95f)) }
                metallic(0f)
                roughness(0.1f)
            }
            transform.scale(Vec3f(0.54f, 0.01f, 0.54f))
            isVisible = false
        }
        glow.isPickable = false
        selectionGlow = glow
    }

    private fun updateSelectionVisuals() {
        val selected = uiState.selectedSquare
        if (selected == null) {
            selectionGlow?.isVisible = false
            clearPathMarkers()
            return
        }
        selectionGlow?.apply {
            isVisible = true
            transform.setIdentity()
            transform.translate(worldX(selected.file), -0.055f, worldZ(selected.rank))
        }
        renderMovePath(uiState.legalMoves)
    }

    private fun clearPathMarkers() {
        pathMarkers.forEach { it.isVisible = false }
        pathMarkers.clear()
    }

    private fun renderMovePath(moves: List<Move>) {
        clearPathMarkers()
        val scene = boardScene ?: return
        moves.forEach { move ->
            val pathSquares = buildPathSquares(move)
            pathSquares.forEach { sq ->
                val marker = scene.addColorMesh {
                    generate { cube { colored() } }
                    shader = KslPbrShader {
                        color { constColor(Color(0.2f, 0.9f, 1f, 0.7f)) }
                        metallic(0f)
                        roughness(0.2f)
                    }
                    transform.scale(Vec3f(0.16f, 0.01f, 0.16f))
                    transform.translate(worldX(sq.file), -0.045f, worldZ(sq.rank))
                }
                marker.isPickable = false
                pathMarkers += marker
            }
        }
    }

    private fun buildPathSquares(move: Move): List<Square> {
        val out = mutableListOf<Square>()
        val dx = move.to.file - move.from.file
        val dz = move.to.rank - move.from.rank
        val stepX = dx.coerceIn(-1, 1)
        val stepZ = dz.coerceIn(-1, 1)
        val length = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz))
        if (length <= 1) return listOf(move.to)
        for (i in 1..length) {
            out += Square(move.from.file + stepX * i, move.from.rank + stepZ * i)
        }
        return out
    }

    private fun clearPieceNodes() {
        pieces.values.forEach { it.node.isVisible = false }
        pieces.clear()
    }

    private fun rebuildAllPieces() {
        game.allPieces().forEach { (square, piece) ->
            val node = createProceduralPiece(piece.type, piece.color, square)
            pieces[square] = PieceVisual(
                node = node,
                type = piece.type,
                color = piece.color,
                square = square,
                worldX = worldX(square.file),
                worldZ = worldZ(square.rank),
                boardFx = square.file.toFloat(),
                boardFz = square.rank.toFloat()
            )
        }
    }

    private fun createProceduralPiece(type: PieceType, color: PieceColor, square: Square): Node {
        val style = styleFor(type, color)
        val root = Node("piece_${type}_${color}_${square.file}_${square.rank}")
        root.transform.translate(worldX(square.file), 0.16f, worldZ(square.rank))
        boardScene!!.addNode(root)

        fun part(scaleX: Float, scaleY: Float, scaleZ: Float, y: Float, c: Color = style.primary) {
            root.addColorMesh {
                generate { cube { colored() } }
                shader = KslPbrShader {
                    color { constColor(c) }
                    metallic(0f)
                    roughness(0.35f)
                }
                transform.scale(Vec3f(scaleX, scaleY, scaleZ))
                transform.translate(0f, y, 0f)
            }
        }

        // Новые силуэты: каждая фигура имеет собственную геометрию / маркеры.
        when (type) {
            PieceType.PAWN -> {
                part(0.15f, 0.06f, 0.15f, 0f)
                part(0.10f, 0.16f, 0.10f, 0.12f)
                part(0.06f, 0.06f, 0.06f, 0.24f, style.accent)
            }
            PieceType.KNIGHT -> {
                part(0.16f, 0.06f, 0.16f, 0f)
                part(0.11f, 0.18f, 0.09f, 0.12f)
                part(0.06f, 0.12f, 0.04f, 0.25f, style.accent) // "шлем"
                part(0.03f, 0.08f, 0.03f, 0.33f, style.accent)
            }
            PieceType.BISHOP -> {
                part(0.16f, 0.06f, 0.16f, 0f)
                part(0.09f, 0.24f, 0.09f, 0.14f)
                part(0.03f, 0.10f, 0.03f, 0.30f, style.accent) // посох
                part(0.08f, 0.02f, 0.02f, 0.34f, style.accent)
            }
            PieceType.ROOK -> {
                part(0.18f, 0.06f, 0.18f, 0f)
                part(0.13f, 0.22f, 0.13f, 0.13f)
                part(0.18f, 0.03f, 0.18f, 0.25f, style.accent) // башенная "корона"
                part(0.03f, 0.05f, 0.03f, 0.30f, style.accent)
                part(0.03f, 0.05f, 0.03f, 0.30f, style.accent).also { root.children.last().transform.translate(-0.10f, 0.30f, -0.10f) }
            }
            PieceType.QUEEN -> {
                part(0.18f, 0.06f, 0.18f, 0f)
                part(0.12f, 0.28f, 0.12f, 0.15f)
                part(0.14f, 0.02f, 0.14f, 0.29f, style.accent)
                part(0.02f, 0.08f, 0.02f, 0.35f, style.accent)
                part(0.08f, 0.02f, 0.02f, 0.34f, style.accent)
                part(0.02f, 0.02f, 0.08f, 0.34f, style.accent)
            }
            PieceType.KING -> {
                part(0.19f, 0.06f, 0.19f, 0f)
                part(0.13f, 0.31f, 0.13f, 0.16f)
                part(0.16f, 0.02f, 0.16f, 0.31f, style.accent)
                part(0.02f, 0.12f, 0.02f, 0.38f, style.accent)
                part(0.10f, 0.02f, 0.02f, 0.38f, style.accent)
                part(0.02f, 0.02f, 0.10f, 0.38f, style.accent)
            }
        }
        tryAttachGltfPiece(root, type, color)
        return root
    }

    private data class PieceStyle(val primary: Color, val accent: Color)

    private fun styleFor(type: PieceType, color: PieceColor): PieceStyle {
        if (color == PieceColor.BLACK) {
            return PieceStyle(
                primary = Color(0.06f, 0.06f, 0.06f, 1f),
                accent = Color(0.16f, 0.16f, 0.16f, 1f)
            )
        }
        return when (type) {
            PieceType.PAWN -> PieceStyle(Color(0.92f, 0.92f, 0.92f, 1f), Color(0.98f, 0.82f, 0.32f, 1f))
            PieceType.KNIGHT -> PieceStyle(Color(0.86f, 0.92f, 1f, 1f), Color(0.20f, 0.55f, 0.98f, 1f))
            PieceType.BISHOP -> PieceStyle(Color(0.90f, 1f, 0.90f, 1f), Color(0.24f, 0.76f, 0.34f, 1f))
            PieceType.ROOK -> PieceStyle(Color(1f, 0.90f, 0.90f, 1f), Color(0.88f, 0.22f, 0.22f, 1f))
            PieceType.QUEEN -> PieceStyle(Color(1f, 0.92f, 1f, 1f), Color(0.74f, 0.26f, 0.86f, 1f))
            PieceType.KING -> PieceStyle(Color(1f, 0.98f, 0.84f, 1f), Color(0.92f, 0.72f, 0.15f, 1f))
        }
    }

    private fun tryAttachGltfPiece(root: Node, type: PieceType, color: PieceColor) {
        val source = resolveModelSource() ?: return
        val gltfPath = source.first

        boardScene?.coroutineScope?.launch {
            val loaded = Assets.loadGltfModel(gltfPath).getOrNull() ?: return@launch
            val key = type.name.lowercase().replaceFirstChar { it.uppercase() }
            loaded.traverse { node ->
                if (node.name.contains(key, ignoreCase = true)) {
                    root.addNode(node)
                    node.transform.scale(0.008f)
                    if (color == PieceColor.BLACK) {
                        node.traverse { sub ->
                            if (sub is Mesh<*>) {
                                sub.shader = KslPbrShader {
                                    color { constColor(Color(0.04f, 0.04f, 0.04f, 1f)) }
                                    metallic(0f)
                                    roughness(0.45f)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun process3dPicking() {
        if (uiState.screen != ScreenState.MATCH) return
        val pointer = PointerInput.primaryPointer
        if (!pointer.isLeftButtonClicked) return

        val scene = boardScene ?: return
        if (!scene.computePickRay(pointer, rayTest.ray)) return
        rayTest.clear(camera = scene.camera)
        scene.rayTest(rayTest)
        val hit = rayTest.hitNode ?: return
        val square = meshToSquare[hit] ?: return
        handleSquareClick(square)
        pointer.consume()
    }

    private fun runAiIfNeeded() {
        if (uiState.mode != GameMode.VS_AI ||
            uiState.screen != ScreenState.MATCH ||
            game.result != GameResult.ONGOING ||
            game.sideToMove != PieceColor.BLACK ||
            pendingAnimations.isNotEmpty()
        ) return

        val aiMove = ai.chooseMove(game, uiState.difficulty) ?: return
        applyMove(aiMove)
        infoText.value = "ИИ сходил: ${fmt(aiMove.from)} -> ${fmt(aiMove.to)}"
    }

    private fun applyMove(move: Move) {
        val moving = pieces.remove(move.from) ?: return
        minimapMoveTraces += MoveTrace(
            fromFile = moving.boardFx,
            fromRank = moving.boardFz,
            toFile = move.to.file.toFloat(),
            toRank = move.to.rank.toFloat()
        )
        val toCapture = if (move.isEnPassant) {
            Square(move.to.file, if (moving.color == PieceColor.WHITE) move.to.rank - 1 else move.to.rank + 1)
        } else move.to
        pieces.remove(toCapture)?.node?.isVisible = false

        if (!game.tryMakeMove(move)) return
        moving.square = move.to
        pieces[move.to] = moving
        pendingAnimations += MoveAnim(
            visual = moving,
            fromX = moving.worldX,
            fromZ = moving.worldZ,
            toX = worldX(move.to.file),
            toZ = worldZ(move.to.rank)
        )

        if (move.isCastling) {
            val rank = if (moving.color == PieceColor.WHITE) 0 else 7
            val rookFrom = if (move.to.file == 6) Square(7, rank) else Square(0, rank)
            val rookTo = if (move.to.file == 6) Square(5, rank) else Square(3, rank)
            val rook = pieces.remove(rookFrom)
            if (rook != null) {
                rook.square = rookTo
                pieces[rookTo] = rook
                pendingAnimations += MoveAnim(rook, rook.worldX, rook.worldZ, worldX(rookTo.file), worldZ(rookTo.rank))
            }
        }
    }

    private fun runAnimations() {
        val it = pendingAnimations.iterator()
        while (it.hasNext()) {
            val anim = it.next()
            anim.elapsed += Time.deltaT
            val t = (anim.elapsed / anim.duration).coerceIn(0f, 1f)
            val nx = anim.fromX + (anim.toX - anim.fromX) * t
            val nz = anim.fromZ + (anim.toZ - anim.fromZ) * t
            val y = anim.baseY + 0.08f * sin(Math.PI.toFloat() * t)
            val dx = nx - anim.visual.worldX
            val dz = nz - anim.visual.worldZ
            anim.visual.node.transform.translate(dx, 0f, dz)
            anim.visual.worldX = nx
            anim.visual.worldZ = nz
            anim.visual.boardFx = boardFileFromWorld(nx)
            anim.visual.boardFz = boardRankFromWorld(nz)
            anim.visual.node.transform.setIdentity()
            anim.visual.node.transform.translate(nx, y, nz)

            if (t >= 1f) it.remove()
        }

        val tr = minimapMoveTraces.iterator()
        while (tr.hasNext()) {
            val t = tr.next()
            t.age += Time.deltaT
            if (t.age >= t.lifeTime) tr.remove()
        }
    }

    private fun animateSelectedPulse() {
        val selected = uiState.selectedSquare ?: return
        val v = pieces[selected] ?: return
        if (pendingAnimations.any { it.visual == v }) return
        val pulse = 0.02f * sin(Time.precisionTime.toFloat() * 6f)
        v.node.transform.setIdentity()
        v.node.transform.translate(v.worldX, 0.16f + pulse, v.worldZ)
        v.node.transform.rotate(8f.deg * Time.deltaT, Vec3f.Y_AXIS)
    }

    private fun updateMiniBoardText() {
        val sb = StringBuilder()
        for (rank in 7 downTo 0) {
            for (file in 0..7) {
                val p = game.pieceAt(Square(file, rank))
                val c = when (p?.type) {
                    PieceType.KING -> if (p.color == PieceColor.WHITE) "K" else "k"
                    PieceType.QUEEN -> if (p.color == PieceColor.WHITE) "Q" else "q"
                    PieceType.ROOK -> if (p.color == PieceColor.WHITE) "R" else "r"
                    PieceType.BISHOP -> if (p.color == PieceColor.WHITE) "B" else "b"
                    PieceType.KNIGHT -> if (p.color == PieceColor.WHITE) "N" else "n"
                    PieceType.PAWN -> if (p.color == PieceColor.WHITE) "P" else "p"
                    null -> "."
                }
                sb.append(c).append(' ')
            }
            sb.append('\n')
        }
        miniBoardText.value = sb.toString()
    }

    private fun UiScope.draw2dBoardPreview() {
        // Используем координаты визуалов (3D), чтобы 2D-иконки двигались синхронно во время анимации.
        val visuals = pieces.values.toList()
        val cell = 38f
        val boardSize = (cell * 8f).dp
        Box {
            modifier.size(boardSize, boardSize)

            Column {
                for (rank in 7 downTo 0) {
                    Row {
                        for (file in 0..7) {
                            val light = (file + rank) % 2 == 0
                            val bg = if (light) Color(0.93f, 0.87f, 0.73f, 1f) else Color(0.33f, 0.25f, 0.16f, 1f)
                            Box {
                                modifier
                                    .size(cell.dp, cell.dp)
                                    .background(RoundRectBackground(bg, 1.dp))
                            }
                        }
                    }
                }
            }

            // Следы ходов на мини-карте (показывают движения с 3D карты в реальном времени).
            minimapMoveTraces.forEach { trace ->
                val t = (trace.age / trace.lifeTime).coerceIn(0f, 1f)
                val curFile = trace.fromFile + (trace.toFile - trace.fromFile) * t
                val curRank = trace.fromRank + (trace.toRank - trace.fromRank) * t
                val xPx = curFile * cell + 14f
                val yPx = (7f - curRank) * cell + 14f
                Box {
                    modifier
                        .size(10.dp, 10.dp)
                        .align(AlignmentX.Start, AlignmentY.Top)
                        .margin(start = xPx.dp, top = yPx.dp)
                        .background(RoundRectBackground(Color(0.15f, 0.9f, 1f, 0.8f), 6.dp))
                }
            }

            // Иконки фигур: позиционируются по непрерывным координатам из 3D анимации.
            visuals.forEach { visual ->
                val xPx = visual.boardFx * cell + 4f
                val yPx = (7f - visual.boardFz) * cell + 4f
                val key = iconKey(Piece(visual.type, visual.color))
                val texture = iconTextures[key]
                if (texture != null) {
                    Image(texture) {
                        modifier
                            .size(30.dp, 30.dp)
                            .align(AlignmentX.Start, AlignmentY.Top)
                            .margin(start = xPx.dp, top = yPx.dp)
                    }
                } else {
                    Text(pieceChar(Piece(visual.type, visual.color))) {
                        modifier
                            .align(AlignmentX.Start, AlignmentY.Top)
                            .margin(start = (xPx + 8f).dp, top = (yPx + 8f).dp)
                    }
                }
            }
        }
    }

    private fun load2dPieceIcons() {
        val base = "C:/Users/Академия/IdeaProjects/FUUUUHengine/src/main/kotlin/chess/icons"
        val files = mapOf(
            "w_pawn" to "spr_pawn_white.png",
            "w_knight" to "spr_knight_white.png",
            "w_bishop" to "spr_bishop_white.png",
            "w_rook" to "spr_tower_white.png",
            "w_queen" to "spr_queen_white.png",
            "w_king" to "spr_king_white.png",
            "b_pawn" to "spr_pawn_black.png",
            "b_knight" to "spr_knight_black.png",
            "b_bishop" to "spr_bishop_black.png",
            "b_rook" to "spr_tower_black.png",
            "b_queen" to "spr_queen_black.png",
            "b_king" to "spr_king_black.png"
        )
        boardScene?.coroutineScope?.launch {
            files.forEach { (k, v) ->
                val tex = Assets.loadTexture2d("$base/$v").getOrNull()
                if (tex != null) iconTextures[k] = tex
            }
        }
    }

    private fun iconKey(piece: Piece): String {
        val c = if (piece.color == PieceColor.WHITE) "w" else "b"
        val t = when (piece.type) {
            PieceType.PAWN -> "pawn"
            PieceType.KNIGHT -> "knight"
            PieceType.BISHOP -> "bishop"
            PieceType.ROOK -> "rook"
            PieceType.QUEEN -> "queen"
            PieceType.KING -> "king"
        }
        return "${c}_$t"
    }

    private fun pieceChar(piece: Piece): String = when (piece.type) {
        PieceType.KING -> if (piece.color == PieceColor.WHITE) "K" else "k"
        PieceType.QUEEN -> if (piece.color == PieceColor.WHITE) "Q" else "q"
        PieceType.ROOK -> if (piece.color == PieceColor.WHITE) "R" else "r"
        PieceType.BISHOP -> if (piece.color == PieceColor.WHITE) "B" else "b"
        PieceType.KNIGHT -> if (piece.color == PieceColor.WHITE) "N" else "n"
        PieceType.PAWN -> if (piece.color == PieceColor.WHITE) "P" else "p"
    }

    private fun setupFigureSourceMessage(): String {
        val modelSource = resolveModelSource()
        return if (modelSource != null) {
            "3D модели подключены: ${modelSource.first}"
        } else {
            "scene.bin не найден, включен процедурный fallback фигур"
        }
    }

    private fun resolveModelSource(): Pair<String, String>? {
        val candidates = listOf(
            "C:/Users/Академия/IdeaProjects/FUUUUHengine/src/main/resources/chess/kit/scene.gltf",
            "C:/Users/Академия/IdeaProjects/FUUUUHengine/src/main/kotlin/chess/scene.gltf",
            "C:/Users/Академия/IdeaProjects/FUUUUHengine/src/main/resources/chess/models/scene.gltf"
        )
        for (gltfPath in candidates) {
            val gltf = File(gltfPath)
            if (!gltf.exists()) continue
            val binPath = "${gltf.parent}/scene.bin"
            if (File(binPath).exists()) return gltfPath to binPath
        }
        return null
    }

    private fun worldX(file: Int): Float = (file - 3.5f) * 1.1f
    private fun worldZ(rank: Int): Float = (rank - 3.5f) * 1.1f
    private fun boardFileFromWorld(x: Float): Float = x / 1.1f + 3.5f
    private fun boardRankFromWorld(z: Float): Float = z / 1.1f + 3.5f

    private fun fmt(square: Square): String = "${'a' + square.file}${square.rank + 1}"
}
