package lesson6

import com.sun.source.tree.Scope
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

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// В настоящей игре очень много событий и процессов заточенных на времени
// Например яд тикает раз в секунду
// Кд способности 1.5 секунд
// Задержка сети 300-200 мили-секунд
// Квест открывает дверь через 10 секунд и тд

// Если все это будет лежать в OnUpdate и таймеры будут обрабатываться в ручную - это быстро превратиться в кашу

// Корутины это решают, они позволяют писать время как обычный код: подождал -> выполнил действие
// Они также не замораживают всю игру и UI
// Удобно прерываются(если яд уже был наложен -> отменить корутину и запустить новую с обновленным эффектом яда) или отменяются

// Основные команды корутины
// launch - запуск корутины
// delay - задерживает корутину на неограниченное число мили-секунд
// Job - контроллер управления корутиной
// cancel - отмена/остановка корутины

// Delay работает только внутри launch потому что delay это suspend функция
// suspend fun - функция, которая может приостанавливаться, обычная функция на это не способна
// suspend функцию можно вызвать только внутри корутины или внутри другой suspend функции

// Использование scene.coroutineScope
// В kool имеет свою корутиную область - почему это удобно:
// когда сцена закрывается, корутины этой сцены тоже прерываются
// это просто безопаснее чем глобальные корутины

class GameState{
    val playerId = mutableStateOf("Олег")

    val hp = mutableStateOf(100)
    val maxHp = 100

    val poisonTicksLeft = mutableStateOf(0)
    val regenTicksLeft = mutableStateOf(0)

    val attackCooldownMsLeft = mutableStateOf(0L)

    val logLines = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String){
    game.logLines.value = (game.logLines.value + text).takeLast(20)
}

// ------------Effect Manager

class EffectManager(
    private val game: GameState,
    private val scope: kotlinx.coroutines.CoroutineScope
    // Передаем сюда область корутин, чтобы при выполнении она была привязана к сцене
){
    private var regenJob: Job? = null
    // Job -это задача карутина, которой мы сможем управлять
    // regenJob - Это ссылка на корутину, чтобы мы могли к ней обращаться и управлять ею
    // null по умолчанию тк корутина по умолчанию не привязана к ссылке

    private var poisonJob: Job? = null

    fun applyPoison(ticks: Int, damagePerTicks: Int, intervalMs: Long){
        // Метод наложение яда на игрока
        // если яд уже был наложен отменяем прошлую корутину
        poisonJob?.cancel()
        // ?. - Безопасный вызов, если poisonJob окажется null то cancel не выполниться

        game.poisonTicksLeft.value += ticks
        // Обновляем счетчик тиков сколько будет действовать эффект яда

        pushLog(game, " Яд применен на ${game.playerId} на длительность ${game.poisonTicksLeft} тиков")

        // Запуск новой корутины действие эффекта яда
        poisonJob = scope.launch {
            while (isActive && game.poisonTicksLeft.value > 0){
                // isActive - существует ли ещё корутина
                delay(intervalMs)
                // Пауза между наследием урона от действия эффекта яда

                game.poisonTicksLeft.value -= 1

                game.hp.value -= (game.hp.value - damagePerTicks).coerceAtLeast(0)
                // Если хп упадет ниже округлит до 0
                pushLog(game, " Тик яда: -$damagePerTicks , Осталось хп: ${game.hp.value}")
            }
            pushLog(game, "Эффект яда завершен")
        }
    }
    fun applyRegen(ticks: Int, healPerticks: Int, intervalMs: Long){
        regenJob?.cancel()

        game.regenTicksLeft.value += ticks
        pushLog(game, "Реген применен на ${game.playerId} длительность ${game.regenTicksLeft}")

        regenJob = scope.launch {
            while (isActive && game.regenTicksLeft.value > 0){
                delay(intervalMs)

                game.regenTicksLeft.value -= 1
                game.hp.value = (game.hp.value + healPerticks).coerceAtMost(game.maxHp)

                pushLog(game, "Тик регена: +$healPerticks ,Осталось Hp: ${game.hp.value}")
            }
            pushLog(game, "Эффект регена завершен")
        }
    }

    fun cancelPoison(){
        poisonJob?.cancel()
        poisonJob = null
        game.poisonTicksLeft.value = 0
        pushLog(game, "Яд снят")
    }

    fun cancelRegen(){
        regenJob?.cancel()
        regenJob = null
        game.regenTicksLeft.value = 0
        pushLog(game, "реген снят")
    }
}

class CooldownManager(
    private val game: GameState,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private val cooldownJob: Job? = null

    fun startAttackCooldown(totalMs: Long){
        cooldownJob?.cancel()

        game.attackCooldownMsLeft.value = totalMs
        pushLog(game, "Кд атаки: ${totalMs}")

        cooldownJob = scope.launch {
            val step = 100L

            while (isActive && game.attackCooldownMsLeft.value > 0L){
                delay(step)
                game.attackCooldownMsLeft.value = (game.attackCooldownMsLeft.value - step)
            }
        }
    }

    fun canAttck(): Boolean{
        return game.attackCooldownMsLeft.value <= 0L
    }
}

fun main() = KoolApplication{
    // создаете экземпляры классов менеджер эфектов и кулдауна
    // делаете кнопки добавления эффекта яда, регена и кулдауна
}