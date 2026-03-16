package lesson5

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.modules.ui2.UiModifier.*
import de.fabmax.kool.math.deg
import jdk.jfr.DataAmount

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.internal.encodeByWriter

import java.io.File
import java.lang.Exception
import java.security.cert.Extension

sealed interface GameEvent {
    val playerId: String
}

data class DamageDealth(
    override val playerId: String,
    val targetId: String,
    val amount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String
): GameEvent

// --------- Серверные данные игрока (те данные которые мы будем сохранять в json файлы)
// @Serializable - аннотация/пометка/указатель на то что класс под данной аннотацией можно сериализовать
@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val questStates: Map<String, String>
)

// --------- ServerWorld c Flow
// Вместо EventBus теперь используем SharedFlow<GameEvent> (Рассыльщик событий)
// Вместо states - StateFlow - для хранения в себе актуального состояния
// Это горячие потоки, которые всегда выполняется в корутине

class ServerWorld(
    initialPlayer: String
){
    private val _events = MutableSharedFlow<GameEvent>(replay = 0)
    //MutableSharedFlow - изменяемый рассыльщик событий, мы можем отправлять события внутрь потока
    //replay - позволяет не присылать старые события новым слушателем
    // События это почти всегда "что случилось сейчас" они не должны повторятся для новых слушателей

    val event: SharedFlow<GameEvent> = _events.asSharedFlow()
    // asSharedFlow() - получить версию только для чтения, через нее публиковать события нельзя, только слушать

    private val _playerState = MutableStateFlow(
        PlayerSave(
            initialPlayer,
            100,
            0,
            mapOf("q_training" to "START")
        )
    )

    val playerState: StateFlow<PlayerSave> = _playerState.asStateFlow()

    // команды общения клиента с сервером
    fun dealDamage(playerId: String, targetId: String, amount: Int){
        val old = _playerState.value

        val newHp = (old.hp - amount).coerceAtLeast(0)

        _playerState.value = old.copy(hp = newHp)
        // copy - data class функция - создает копию объекта с измененным полем
    }
    fun questStateChanged(playerId: String, questId: String, newState: String){
        val old = _playerState.value

        // + создает новую Map и не ломает старую Map
        val newQuestState = old.questStates + (questId to newState)

        _playerState.value = old.copy(questStates = newQuestState)
    }

    // suspend - функция которая может ждать(delay/emit)
    suspend fun emitEvent(event: GameEvent){
        _events.emit(event)
        // emit() - отправить событие всем слушателем
        // emit может подождать если подписчики медленные
    }
}

// Сериализация объекта в строку json
class SaveSystem{
    private val json = Json{
        prettyPrint = true
        encodeDefaults = true
    }
    // prettyPrint - делает json красивым и читаемым
    // encodeDefaults - записывает значения по умолчанию

    private fun saveFile(playerId: String): File{
        val dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$playerId.json")
    }

    fun save(player: PlayerSave){
        val text = json.encodeToString(player)
        // encodeToString - превращает наш объект в строку json файла

        saveFile(player.playerId).writeText(text)
    }

    fun load(playerId: String): PlayerSave? {
        val file = saveFile(playerId)
        if (!file.exists()) return null

        val text = file.readText()

        return try {
            json.decodeFromString<PlayerSave>(text)
            // Преобразование текста из строки json в объект PlayerSave
        }catch (e: Exception){
            return null
        }
    }
}



class  UiState{
    // Создать состояние, хранящие активного игрока по умолчанию Олег
    // То же самое для hp и gold
    // Создать состояние хранящие questState - изменяемым состоянием по умолчанию "START"

    // Состояние logLines изменяемое - хранящая типы данных, Список только со строками -> по умолчанию пустой список
}