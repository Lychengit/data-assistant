package com.djzy.assistant.runtime.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 假的沙箱后端（测试用）：只记「哪些命令在我这儿跑过」「我手里有哪些文件」。
 *
 * <p>它存在的意义是让用例**不需要 Docker** 也能断言「这次调用到底落到谁手里」——
 * 路由的语义（命令归沙箱、命中前缀的读写归共享库、前缀被剥掉之后对面看到什么路径）
 * 都是纯逻辑，跑一个真容器只会让用例更慢、更依赖环境。
 *
 * <p>真容器那一段（投影、镜像、docker CLI）由 {@code SandboxDockerEndToEndTest} 兜。
 */
class FakeSandboxFilesystem implements AbstractSandboxFilesystem {

    final Map<String, String> files = new LinkedHashMap<>();
    final List<String> executedCommands = new ArrayList<>();

    @Override
    public String id() {
        return "fake-sandbox";
    }

    @Override
    public ExecuteResponse execute(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        executedCommands.add(command);
        return new ExecuteResponse("done: " + command, 0, false);
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        return LsResult.success(List.of());
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        String content = files.get(filePath);
        return content == null
                ? ReadResult.fail("no such file: " + filePath)
                : ReadResult.success(FileData.create(content));
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        if (files.containsKey(filePath)) {
            return WriteResult.fail("already exists: " + filePath);
        }
        files.put(filePath, content);
        return WriteResult.ok(filePath);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        String content = files.get(filePath);
        if (content == null) {
            return EditResult.fail("no such file: " + filePath);
        }
        files.put(filePath, content.replace(oldString, newString));
        return EditResult.ok(filePath, 1);
    }

    @Override
    public GrepResult grep(RuntimeContext runtimeContext, String pattern, String path, String glob) {
        return GrepResult.success(List.of());
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        return GlobResult.success(List.of());
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> uploads) {
        List<FileUploadResponse> responses = new ArrayList<>();
        for (Map.Entry<String, byte[]> upload : uploads) {
            files.put(upload.getKey(), new String(upload.getValue(), StandardCharsets.UTF_8));
            responses.add(FileUploadResponse.success(upload.getKey()));
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> responses = new ArrayList<>();
        for (String path : paths) {
            String content = files.get(path);
            responses.add(
                    content == null
                            ? FileDownloadResponse.fail(path, "no such file")
                            : FileDownloadResponse.success(path, content.getBytes(StandardCharsets.UTF_8)));
        }
        return responses;
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        files.remove(path);
        return WriteResult.ok(path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        String content = files.remove(fromPath);
        if (content == null) {
            return WriteResult.fail("no such file: " + fromPath);
        }
        files.put(toPath, content);
        return WriteResult.ok(toPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        return files.containsKey(path);
    }
}
