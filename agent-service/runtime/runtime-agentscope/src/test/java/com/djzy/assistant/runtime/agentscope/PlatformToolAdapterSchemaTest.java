package com.djzy.assistant.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.spi.tool.SideEffect;
import com.djzy.assistant.spi.tool.ToolCategory;
import com.djzy.assistant.spi.tool.ToolSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 模型面的入参 schema（§18.4.4 / §18.5.3）：**宿主代理会填的字段，不能要求模型填**。
 *
 * <p>守的是一个实测出来的死结（2026-09-27）：技能文档让模型「只给 sandbox_path、不要抄 base64」，
 * 而模型面的 schema 照注册契约把 {@code content_base64} 标成必填，于是框架在**平台管线之前**
 * 就把这次调用打回（{@code 未找到所需属性"content_base64"}）——写操作一次都没发出去，
 * 用户看到的是「点了确认还是失败」，还要再确认第二次（模型重试）。
 */
class PlatformToolAdapterSchemaTest {

    /** 接口服务的真实契约：content_base64 必填（宿主代理一定会填上）。 */
    private static Map<String, Object> uploadSchema() {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("type", "string");
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "string");
        content.put("description", "文件内容的 base64。单文件上限 20MB");
        Map<String, Object> sandboxPath = new LinkedHashMap<>();
        sandboxPath.put("type", "string");
        sandboxPath.put("description", "可选。沙箱内产物路径");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("file_name", file);
        properties.put("content_base64", content);
        properties.put("sandbox_path", sandboxPath);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("file_name", "content_base64"));
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Test
    void 有_sandbox_path_时_不再要求模型填_content_base64() {
        Map<String, Object> relaxed = PlatformToolAdapter.modelFacingSchema(uploadSchema());

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) relaxed.get("required");
        assertEquals(List.of("file_name"), required, "宿主代理会填的字段不该留在 required 里");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) relaxed.get("properties");
        assertTrue(properties.containsKey("content_base64"), "属性要留着：删掉它 + additionalProperties=false 会让模型一填就非法");
        assertEquals(false, relaxed.get("additionalProperties"), "其余约束原样保留");
    }

    @Test
    void 两个字段的说明都改成模型照着做就能通过的说法() {
        Map<String, Object> relaxed = PlatformToolAdapter.modelFacingSchema(uploadSchema());
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) relaxed.get("properties");

        String base64Hint = String.valueOf(((Map<?, ?>) properties.get("content_base64")).get("description"));
        assertTrue(base64Hint.contains("不用填"), "要明说不用填，实际：" + base64Hint);
        String pathHint = String.valueOf(((Map<?, ?>) properties.get("sandbox_path")).get("description"));
        assertTrue(pathHint.contains("推荐只给这个"), "要把推荐做法写进 schema，实际：" + pathHint);
        assertEquals("string", ((Map<?, ?>) properties.get("file_name")).get("type"), "别的属性不许被动");
    }

    @Test
    void 没有_sandbox_path_的接口原样不动() {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> month = new LinkedHashMap<>();
        month.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("month", month);
        schema.put("type", "object");
        schema.put("required", List.of("month"));
        schema.put("properties", properties);

        assertEquals(schema, PlatformToolAdapter.modelFacingSchema(schema), "不该顺手改掉别的接口的契约");
    }

    @Test
    void 工具注册给框架的就是放宽后的这一份() {
        ToolSpec spec = new ToolSpec(
                "iface_doctor_export_upload",
                "上传",
                uploadSchema(),
                SideEffect.WRITE,
                ToolCategory.IFACE,
                Set.of("api"));

        PlatformToolAdapter adapter = new PlatformToolAdapter(spec);

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) adapter.getParameters().get("required");
        assertFalse(required.contains("content_base64"), "校验用的是这一份 schema，放宽必须落在它身上");
        assertTrue(required.contains("file_name"), "别的必填项不能被顺手删掉");
    }
}
