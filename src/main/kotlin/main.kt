    import de.fabmax.kool.KoolApplication           // Запускает движок
    import de.fabmax.kool.addScene                  // Добавление сцены (игра, Ui, меню, уровень)
    import de.fabmax.kool.math.Vec3f                // 3д вектор (x, y, z)
    import de.fabmax.kool.math.deg                  // deg - превращение числа в градусы
    import de.fabmax.kool.scene.*                   // Сцена, камера, создание фигур, освещение
    import de.fabmax.kool.modules.ksl.KslPbrShader  // Шейдеры - материал обьекта
    import de.fabmax.kool.util.Color                // Цветовая палитра (RGBA)
    import de.fabmax.kool.util.Time                 // Время - Time.deltaT - сколько секунд пройдет между кадрами
    import de.fabmax.kool.pipeline.ClearColorLoad   // Не стекать элемент уже отрисованый на экране. Ui - всегда поверэ всего на сцене
    import de.fabmax.kool.modules.ui2.*             // Слздание текста , кнопокm , панелий
    import de.fabmax.kool.modules.ui2.UiModifier.*  //

    class GameState{
        val playerId = mutableStateOf("Player")
        // mutableStateOf - Создает состояние, за которым будет следить ui элемент
        val hp = mutableStateOf(100)
        val gold = mutableStateOf(0)
        val potionTicksLeft = mutableStateOf(0)
        // Tick - словная единица времени, на которую мы опираемся, чтобы не зависеть от клиентского FPS
        // На нашем примере 1 тик = 1 секунде, объем тика определяется разработчиком игры самостоятельно
    }

    // KoolApplication - указывает, что запускаемое приложение - приложения написанное на Kool
    fun main() = KoolApplication{
        // Запуск движка
        val game = GameState()

        // 1 сцена - игровой мир
        addScene {
            defaultOrbitCamera()

            addColorMesh {
                generate {
                    cube{
                        colored()
                        // Добавляем цвет в стороны куба
                    }
                }
                shader = KslPbrShader {
                    // Назначаем материал
                    color { vertexColor() }
                    // берем подготовленные цвета из сторон куба
                    metallic(0f)        // 0f - пластик. 1f - кусок метала
                    roughness(0.25f)    // 0 - глянец. 1 - матовый
                }

                onUpdate{
                    transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
                    // rotate(угол, ось)
                    // 45 - градусы
                    // * Time.deltaT - сколько прошло секунд между кадрами
                }
            }

            lighting.singleDirectionalLight {
                setup(Vec3f(-1f,-1f,-1f))
                // Установили в позицию немного дальше от центра
                setColor(Color.WHITE, 5f)
            }

            var potionTimerSec = 0f
            onUpdate{
                if (game.potionTicksLeft.value > 0){
                    potionTimerSec += Time.deltaT

                    if (potionTimerSec >= 1f){
                        potionTimerSec = 0f

                        game.potionTicksLeft.value --

                        game.hp.value = (game.hp.value - 2).coerceAtLeast(0)

                    }
                }else{
                    potionTimerSec = 0f
                }
            }
        }


            addScene {
                setupUiScene(ClearColorLoad)

                addPanelSurface{
                    modifier
                        .size(360.dp, 210.dp)
                        .align(AlignmentX.Start, AlignmentY.Top)
                        .padding(16.dp)
                        .background(RoundRectBackground(Color(0f, 0f, 0f, 0.5f), 14.dp))

                    Column {
                        Text("Игрок: ${game.playerId.use()}"){}
                        Text("Hp: ${game.hp.use()}"){}
                        Text("Голда: ${game.gold.use()}"){}
                        Text("Действие зелья: ${game.potionTicksLeft.use()}"){}
                    }
                }
            }

    }