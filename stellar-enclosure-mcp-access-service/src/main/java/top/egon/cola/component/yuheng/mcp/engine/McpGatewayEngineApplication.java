package top.egon.cola.component.yuheng.mcp.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import top.egon.cola.component.yuheng.mcp.engine.mcp.dao.McpApprovalDAO;
import top.egon.cola.component.yuheng.mcp.engine.mcp.dao.McpTaskDAO;

/** Starts the dedicated MCP protocol runtime and its scoped management actions. */
@SpringBootApplication(scanBasePackages = {
        "top.egon.cola.component.yuheng.mcp.engine.bootstrap",
        "top.egon.cola.component.yuheng.mcp.engine.config",
        "top.egon.cola.component.yuheng.mcp.engine.mcp",
        "top.egon.cola.component.yuheng.admin.mcp.controller",
        "top.egon.cola.component.yuheng.admin.mcp.service"
})
@MapperScan(basePackageClasses = {McpTaskDAO.class, McpApprovalDAO.class})
public class McpGatewayEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpGatewayEngineApplication.class, args);
    }
}
