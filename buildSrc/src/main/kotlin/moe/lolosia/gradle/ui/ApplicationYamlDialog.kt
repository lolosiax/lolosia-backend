package moe.lolosia.gradle.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CountDownLatch
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

sealed class YamlDialogResult {
    data class UseContent(val content: String) : YamlDialogResult()
    object CreateEmpty : YamlDialogResult()
    object Abort : YamlDialogResult()
}

object ApplicationYamlDialog {

    fun show(defaultContent: String): YamlDialogResult {
        val latch = CountDownLatch(1)
        var result: YamlDialogResult = YamlDialogResult.Abort

        SwingUtilities.invokeLater {
            val dialog = JDialog(null as Frame?, "远程服务器缺少配置文件", Dialog.ModalityType.APPLICATION_MODAL)
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE)

            val rootPanel = JPanel(BorderLayout(10, 10))
            rootPanel.border = EmptyBorder(15, 15, 15, 15)

            val hasLocalhost = defaultContent.contains("localhost")

            val topPanel = JPanel(BorderLayout(5, 8))

            if (hasLocalhost) {
                val warningLabel = JLabel("<html><b>&#9888; 检测到配置中包含 localhost</b> — 部署到远程服务器后，容器内访问宿主机应使用 <b>172.17.0.1</b> 而非 localhost，请确认是否需要修改。</html>")
                warningLabel.foreground = Color.RED
                warningLabel.font = warningLabel.font.deriveFont(Font.BOLD, 13f)
                topPanel.add(warningLabel, BorderLayout.NORTH)
            }

            val descLabel = JLabel("<html>远程服务器工作目录下未找到 <b>application.yaml</b>。<br>下方已自动填入当前开发环境的配置内容，请根据生产环境实际情况修改后确认：</html>")
            descLabel.font = descLabel.font.deriveFont(13f)
            topPanel.add(descLabel, BorderLayout.CENTER)

            val textArea = JTextArea(defaultContent, 22, 70)
            textArea.font = Font("Monospaced", Font.PLAIN, 13)
            textArea.tabSize = 2
            val scrollPane = JScrollPane(textArea)
            scrollPane.preferredSize = java.awt.Dimension(800, 450)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0))
            val createEmptyBtn = JButton("创建空的")
            val useContentBtn = JButton("使用上述内容")
            val abortBtn = JButton("中止部署")

            fun disposeWith(value: YamlDialogResult) {
                result = value
                dialog.dispose()
                latch.countDown()
            }

            createEmptyBtn.addActionListener { disposeWith(YamlDialogResult.CreateEmpty) }
            useContentBtn.addActionListener { disposeWith(YamlDialogResult.UseContent(textArea.text)) }
            abortBtn.addActionListener { disposeWith(YamlDialogResult.Abort) }

            dialog.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) {
                    disposeWith(YamlDialogResult.Abort)
                }
            })

            buttonPanel.add(createEmptyBtn)
            buttonPanel.add(useContentBtn)
            buttonPanel.add(abortBtn)

            rootPanel.add(topPanel, BorderLayout.NORTH)
            rootPanel.add(scrollPane, BorderLayout.CENTER)
            rootPanel.add(buttonPanel, BorderLayout.SOUTH)

            dialog.setContentPane(rootPanel)
            dialog.pack()
            dialog.setLocationRelativeTo(null)
            dialog.isVisible = true
        }

        latch.await()
        return result
    }
}