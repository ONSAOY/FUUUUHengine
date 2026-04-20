package PlayerKeyboardMovement

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.input.KeyCode
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.modules.ui2.UiModifier.*
import de.fabmax.kool.math.deg
import game.Move

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlin.math.sqrt // квадратный корень числа(длина вектора и растояний)

// Импорты библиотеки desktop Keyboard bridge (JVM)
import java.awt.KeyEventDispatcher
// KeyEventDispatcher - перехватчик событий клавиатуры
// То есть, как объект, который видит, что мы нажимаем на клавиатуре

import java.awt.KeyboardFocusManager
// KeyboardFocusManager - менеджер фокусов окна
// Он нужен, чтобы добраться до системы ввода клавиатуры внутри активного окна

import java.awt.event.KeyEvent
// KeyEvent - событие нажатия клавиши
// В нем хранится: 1 - какая клавиша нажата, 2 - нажали или отпустили ее

// Mat formuli
import kotlin.math.abs
// abs(x) - модуль числа x
// abs(-3) = 3

import kotlin.math.atan2
// atan2(...) - функция для вычисления угла направления
// Она нужна для:
// 1 - понимания под каким углом должен смотреть игрок если идет в определенную сторону

import kotlin.math.cos
// Нужен для высчитывания направлений

// Он не двигает игрока
object DesktopKeyboardState{
    private val pressedKeys = mutableSetOf<Int>()
    // pressedKeys - набор кодов клавиш, которые сейчас зажаты
    // Set - набор уникальных чисел

    private val justPressedKeys = mutableSetOf<Int>()
    // justPressedKeys - набор клавиш, которые нажали вот, вот только что
    // Удобно для действий вроде:
    // любых одиночных разовых действий
    // Если не сделать этого, то клавиша взаимодействия E при удержании будет срабатывать каждый кадр

    private var isInstalled = false
    // isInstalled - флаг установки перехватчика клавиатуры
    // Нужен для: того чтобы команды не накладывались на, друг дуга

    fun install(){
        // install - установка слушателя клавиатуры
        // Делать это будем 1 раз, в самом начале программы

        if (isInstalled) return

        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(
                object : KeyEventDispatcher{
                    // object : Type {...} - это создание анонимного объекта
                    // "Создать объект перехватчик клавиатуры"

                    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                        // dispatchKeyEvent(...) - метод, который будет вызываться при событии нажатия на клавишу
                        when(e.id){
                            // проверяем тип клавиши
                            KeyEvent.KEY_PRESSED -> {
                                // KEY_PRESSED - клавиша нажата
                                if (!pressedKeys.contains(e.keyCode)){
                                    // .contains() - не нажата ли она ещё
                                    justPressedKeys.add(e.keyCode)
                                    // keyCode - код клавиши клавиатуры
                                }

                                pressedKeys.add(e.keyCode)
                            }

                            KeyEvent.KEY_RELEASED -> {
                                // События когда клавишу отпустили
                                pressedKeys.remove(e.keyCode)
                                justPressedKeys.remove(e.keyCode)
                                // Если клавишу отпустили - удалить из набора клавиш
                            }
                        }

                        return false
                        // Значит не блокировать это событие пусть событие его видит
                    }
                }
            )
        isInstalled = true
        // Слушатель уже поставлен и слушает
    }
    fun isDown(keyCode: Int): Boolean{
        // Нажата ли сейчас конкретная клавиша
        return keyCode in pressedKeys
    }

    fun consumeJustPressed(keyCode: Int): Boolean{
        // Ловим клавишу 1 раз
        return if (keyCode in justPressedKeys){
            justPressedKeys.remove(keyCode)
            true
        }else{
            false
        }
    }
}

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST,
    DOOR
}

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectDef,
    val wordX: Float,
    val wordZ: Float,
    val interactRadius: Float
)

data class ObstacleDef(
    val centerX: Float,
    val centerZ: Float,
    val halfSize: Float
)
// Половина размера объекта удобна для определения столкновения с объектом по осям

data class NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receivedHerb: Boolean
)

data class PlayerState(
    val playerId: String,

    val centerX: Float,
    val centerZ: Float,

    val yawDeg: Float,

    val moveSpeed: Float,

    val questState: QuestState,
    val inventory: Map<String, Int>,
    val gold: Int,

    val alchemistMemory: NpcMemory,

    val chestLooted: Boolean,
    val doorOpened: Boolean,

    val currentFocusId: String?,

    val hintText: String,
    val pinnedQuestEnabled: Boolean,
    val pinnedTargetId: String?
)

fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}

fun larp(current: Float, target: Float, t:Float): Float{
    // Линейная интерполяция - нужна для перемещения обьекта от 1 точки к другой
    return current + (target - current) * t
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    // Расчет расстояния между двумя точками на плоскости XZ
    // Школьная формула расстояния:
    // sqrt((dx * dx) + (dz * dz))
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx * dx + dz * dz)
}

fun normilizeOrZero(x: Float, z: Float): Pair<Float, Float>{
    // функция определяет произвольный вектор движения
    // Нужно если игрок зажал W и D одновременно, то движение будет быстрее
    // Это ошибка, поэтому нормализуем вектор движения

    val len = sqrt(x*x + z*z)

    return if (len <= 0.0001f){
        // Если длина почти ноль -> Игрок не может двигаться
        0f to 0f
    }else{
        (z / len) to (z/len)
        // После этой операции длина векторамтанет равно примерно 1
    }
}

fun computeYawDegDirection(dirX: Float, dirZ: Float): Float{
    // Проверка под каким углом надо смотреть игроку
    val raw = Math.toDegrees(atan2(dirX.toDouble() , dirZ.toDouble())).toFloat()
    // atan2 - позволяет, зная направление получить угол куда смотрит игрок
    // Math.toDegrees(...) - это преобразование в градусы
    // atan - возвращает угол в радианах, а нам нужны градусы
    // Double преобразовывает тк atan2 ожидает именно double, и он более точный
    return if (raw < 0f) raw + 360f else raw
    // Зачем?
    // atan может вернуть угол в минусовом значении
    // Нам нужно держать градусы в диапазоне т 0 до 360
    // Так что, чтобы преобразовать в положительное
}

fun initialPlayerState(playerId: String): PlayerState {
    return if (playerId == "Sas"){
        PlayerState(
            "Sas",
            0f,
            0f,
            0f,
            3.2f,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                true,
                2,
                false
            ),
            false,
            false,
            null,
            "Подойди к любой области на карте",
            true,
            "alchemist"
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            0f,
            3.2f,
            QuestState.START,
            emptyMap(),
            2,
            NpcMemory(
                false,
                0,
                false
            ),
            false,
            false,
            null,
            "Подойди к любой области на карте",
            true,
            "alchemist"
        )
    }
}

fun computePinnedTargetId(player: PlayerState): String?{
    if (!player.pinnedQuestEnabled) return null
    
    val herbs = herbCount(player)
    
    return when(player.questState){
        QuestState.START -> "alchemist"
        
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "herb_source" else "alchemist"
        }

        QuestState.GOOD_END -> {
            if (!player.chestLooted) "reward_chest"
            else if(!player.doorOpened) "door"
            else null
        }

        QuestState.EVIL_END -> null
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

fun buildAchemistDialogue(player: PlayerState): DialogueView {
    // Теперь показываем активный диалог только если в фокусе игрока именно алхимик

    if (player.currentFocusId != "alchemist"){
        return DialogueView(
            npcName = "Алхимик",
            text = "Эй сюда смотри!",
            options = emptyList()
        )
    }

    val herbs = herbCount(player)
    val memory= player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet){
                    "Новое лицо, я тебя не помню"
                }else{
                    ""
                }
            DialogueView(
                "Алхимик",
                "$greeting \n Если хочешь варить траву, собери ее для начала",
                listOf(
                    DialogueOption(
                        "accept_help",
                        "Я буду варить"
                    ),
                    DialogueOption(
                        "threat",
                        "Давай сюда товар, быстро"
                    )
                )
            )
        }

        QuestState.WAIT_HERB -> {
            if (herbs < 3) {
                DialogueView(
                    "Алхимик",
                    "Пока ты собрал только $herbs/4 травы, возвращайся с полным товаром",
                    emptyList()
                )
            } else {
                DialogueView(
                    "Алхимик",
                    "Отличный товар, давай сюда!",
                    listOf(
                        DialogueOption(
                            "give_herb",
                            "Отдать 4 травы"
                        )
                    )
                )
            }
        }
        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb){
                    "Спасибо, я теперь точно много зелий наварю, можешь забрать свою награду"
                }else{
                    "Ты завершил квест, но память не обновилась"
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }
        QuestState.EVIL_END -> {
            DialogueView(
                "Алхимик",
                "Я не хочу с тобой разговаривать",
                emptyList()
            )
        }
    }
}

sealed interface GameCommand {
    val playerId: String
}

data class CmdMoveAxis(
    override val playerId: String,
    val axisX: Float,
    val axisZ: Float,
    val deltaSec: Float
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand

data class CmdTogglePinnedQuest(
    override val playerId: String
): GameCommand

sealed interface GameEvent {
    val playerId: String
}

data class PlayerMoved(
    override val playerId: String,
    val newWorldX: Float,
    val newWorldZ: Float
): GameEvent

data class MovementBlocked(
    override val playerId: String,
    val blockedWorldX: Float,
    val blockedWorldZ: Float
): GameEvent

data class FocusChanged(
    override val playerId: String,
    val newFocusId: String?
): GameEvent

data class PinnedTargetChanged(
    override val playerId: String,
    val newTarhetId: String?
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent
