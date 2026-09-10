package com.rustik.miniautoclicker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Автокликер Mini App — v3"
            textSize = 23f
        }

        val help = TextView(this).apply {
            text = "\nРусская версия.\n" +
                    "Детектор ищет препятствия в игровой области Telegram и нажимает для прыжка, когда препятствие приближается.\n\n" +
                    "1. Разреши службу в «Специальных возможностях».\n" +
                    "2. Включи автокликер.\n" +
                    "3. Открой игру в Telegram.\n" +
                    "4. Нажми «Играть?».\n\n" +
                    "Для первого теста лучше оставить экран игры полностью видимым."
            textSize = 16f
        }

        val settings = Button(this).apply {
            text = "1. Открыть Специальные возможности"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val toggle = Button(this).apply {
            text = if (ClickAccessibilityService.enabledByUser)
                "Автокликер: ВКЛ"
            else
                "2. Включить автокликер"

            setOnClickListener {
                ClickAccessibilityService.enabledByUser =
                    !ClickAccessibilityService.enabledByUser
                text = if (ClickAccessibilityService.enabledByUser)
                    "Автокликер: ВКЛ"
                else
                    "2. Включить автокликер"

                status.text = if (ClickAccessibilityService.enabledByUser)
                    "Статус: включён. Жду Telegram…"
                else
                    "Статус: выключен"
            }
        }

        val test = Button(this).apply {
            text = "Тестовый прыжок"
            setOnClickListener {
                if (ClickAccessibilityService.service != null) {
                    ClickAccessibilityService.service?.testJump()
                    Toast.makeText(
                        this@MainActivity,
                        "Тестовый жест отправлен",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Сначала включи службу Accessibility",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        status = TextView(this).apply {
            text = "Статус: служба не проверена"
            textSize = 16f
        }

        box.addView(title)
        box.addView(help)
        box.addView(settings)
        box.addView(toggle)
        box.addView(test)
        box.addView(status)

        setContentView(box)
    }
}
