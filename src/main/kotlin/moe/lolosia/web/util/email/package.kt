package moe.lolosia.web.util.email

import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import moe.lolosia.web.util.spring.ApplicationContextProvider

/**
 * 发送邮件至指定的邮箱
 * @param to 邮箱
 * @param title 标题
 * @param html 邮件内容
 */
fun ApplicationContextProvider.sendMail(to: String, title: String, html: String){
    val mailSender = applicationContext.getBean(JavaMailSender::class.java)
    val message = mailSender.createMimeMessage()
    val helper = MimeMessageHelper(message, false)
    helper.setFrom("aigc@lolosia.top", "人工智能实验室")
    helper.setTo(to)
    helper.setSubject("$title - 学金智能AI平台")
    helper.setText(html, true)
    mailSender.send(message)
}