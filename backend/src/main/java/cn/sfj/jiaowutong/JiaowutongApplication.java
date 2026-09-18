package cn.sfj.jiaowutong;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class JiaowutongApplication {
    public static void main(String[] args) {
        // 不依赖容器 TZ 环境变量：服务端时钟、LocalDateTime、Jackson 统一上海时区
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(JiaowutongApplication.class, args);
    }
}
