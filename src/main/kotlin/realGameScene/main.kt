package realGameScene

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
import org.w3c.dom.Text
import kotlin.math.sqrt

// Типы обьектов игрового мира

enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    BAD_END
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE
}

// Описания обьекта в мире
data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float
)

// Память Npc - о конкретном игроке (прогресс квестов)
data class NpcMemory(
    val hasMet: Boolean,        // Встретился ли игрок уже с NPC
    val timesTalked: Int,       // Сколько раз поговорил
    val receivedHerb: Boolean   // Отдали ли уже траву
)

// Состояние игрока на сервере
data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posZ: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,          // В какой локации накодится (может быть null)
    val hintText: String
)

// ------ Основные функции
fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    // Расчет расстояния между двумя точками на плоскости XZ
    // Школьная формула расстояния:
    // sqrt((dx * dx) + (dz * dz))
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx * dx + dz * dz)
}

fun initialPlayerState(playerId: String): PlayerState{
    // Разделение на нескольких игроков

    return if (playerId == "Sas"){
        PlayerState(
            "Sas",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                true,
                2,
                false
            ),
            null,
            "Подойди к любой области на карте"
        )
    }else{
        PlayerState(
            "Tyler",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                true,
                2,
                false
            ),
            null,
            "Подойди к любой области на карте"
        )
    }
}

// Диалоговая модель для Hud

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val option: List<DialogueOption>
)

fun buildAchemistDialogue(player: PlayerState): DialogueView{
    val herbs = herbCount(player)
    val memory= player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet){
                    ""
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
            if (herbs < 3){
                DialogueView(
                    "Алхимик",
                    "Пока ты собрал только $herbs/4 травы, возвращайся с полным товаром",
                    emptyList()
                )
            }else{
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
            QuestState.GOOD_END -> {
                val text =
                    if (memory.receivedHerb){
                        "Спасибо, я теперь точно много зелий наварю, я тебя запомнил, заходи ещё"
                    }else{
                        "Ты завершил квест, но память не обновилась"
                    }
                DialogueView(
                    "Алхимик",
                    text,
                    emptyList()
                )
            }

        }
    }
}