package dev.sixbridge.probe.generate

import dev.sixbridge.probe.catalog.ApiProbeCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class ProbeSubmissionTest {
    @Test
    fun `NOT_RUN이 남아 있으면 전송하지 않고 마지막 gesture 후 exact POST를 1회 수행한다`() = withWorkspace { root ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), root.resolve("generated"))
        val module = root.resolve("probe-submission.mjs")
        Files.copy(generated.first().sourceRoot.resolve("probe-submission.js"), module)
        runNode(root, """
            import { createSubmissionController } from '${module.toUri()}';
            const calls = [];
            const controller = createSubmissionController({
              catalogSha256: '${"a".repeat(64)}',
              caseIds: ['gesture-b', 'auto-a', 'gesture-a'],
              fetchImpl: async (url, options) => { calls.push({ url, options }); return { status: 201 }; },
              groupId: 'base-runtime',
            });
            await controller.recordResult(result('auto-a', 'VERIFIED'));
            await controller.recordResult(result('gesture-b', 'NOT_RUN'));
            if (calls.length !== 0) throw new Error('NOT_RUN was submitted');
            await controller.recordResult(result('gesture-b', 'EXPOSED'));
            if (calls.length !== 0) throw new Error('incomplete snapshot was submitted');
            await controller.recordResult(result('gesture-a', 'ABSENT'));
            if (calls.length !== 1) throw new Error(`post count=${'$'}{calls.length}`);
            const call = calls[0];
            if (call.url !== 'http://127.0.0.1:47171/evidence') throw new Error(call.url);
            if (call.options.method !== 'POST' || call.options.credentials !== 'omit' || call.options.redirect !== 'error') throw new Error('fetch contract');
            if (call.options.headers['Content-Type'] !== 'application/json') throw new Error('content type');
            const parsed = JSON.parse(call.options.body);
            if (Object.keys(parsed).join(',') !== 'catalogSha256,groupId,results,schemaVersion') throw new Error('root canonical order');
            if (parsed.results.map((value) => value.caseId).join(',') !== 'auto-a,gesture-a,gesture-b') throw new Error('case sort');
            function result(caseId, status) {
              const detail = status === 'VERIFIED' ? { kind: 'success', valueType: 'object' }
                : status === 'EXPOSED' ? { kind: 'exposed' }
                : status === 'ABSENT' ? { kind: 'absent' }
                : { kind: 'gesture-required' };
              return { caseId, context: status === 'NOT_RUN' ? 'user_gesture' : 'extension_page', detail, durationMs: 0, member: 'member', namespace: 'runtime', runToken: 'run-token-00000001', status };
            }
        """.trimIndent())
    }

    @Test
    fun `retry는 frozen bytes만 재사용하고 409는 세션 갱신 없이 종료한다`() = withWorkspace { root ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), root.resolve("generated"))
        val module = root.resolve("probe-submission.mjs")
        Files.copy(generated.first().sourceRoot.resolve("probe-submission.js"), module)
        runNode(root, """
            import { createSubmissionController } from '${module.toUri()}';
            const bodies = [];
            const statuses = [503, 200];
            const controller = createSubmissionController({
              catalogSha256: '${"a".repeat(64)}', caseIds: ['one'], groupId: 'base-runtime',
              fetchImpl: async (_url, options) => { bodies.push(options.body); return { status: statuses.shift() }; },
            });
            const value = { caseId: 'one', context: 'extension_page', detail: { kind: 'exposed' }, durationMs: 0, member: 'getURL', namespace: 'runtime', runToken: 'run-token-00000001', status: 'EXPOSED' };
            await controller.recordResult(value);
            value.member = 'mutated';
            await controller.retry();
            if (bodies.length !== 2 || bodies[0] !== bodies[1]) throw new Error('retry bytes changed');
            const conflicts = [];
            const conflict = createSubmissionController({
              catalogSha256: '${"a".repeat(64)}', caseIds: ['one'], groupId: 'base-runtime',
              fetchImpl: async (_url, options) => { conflicts.push(options.body); return { status: 409 }; },
            });
            await conflict.recordResult({ ...value, member: 'getURL' });
            await conflict.retry();
            if (conflicts.length !== 1 || conflict.state() !== 'CONFLICT') throw new Error('409 must be terminal');
        """.trimIndent())
    }

    @Test
    fun `generated UI는 gesture를 하나의 queue로 순차 실행한다`() = withWorkspace { root ->
        val source = ProbeSourceGenerator().generate(loadCatalog(), root.resolve("generated")).first().sourceRoot
        val ui = source.resolve("probe-ui.js").readText()
        assertTrue("gestureQueue" in ui)
        assertTrue("runNextGesture" in ui)
    }

    @Test
    fun `actual generated UI는 response loss 후 retry button으로 동일 frozen bytes를 재전송한다`() = withWorkspace { root ->
        val source = ProbeSourceGenerator().generate(loadCatalog(), root.resolve("generated")).first().sourceRoot
        Files.writeString(source.resolve("package.json"), "{\"type\":\"module\"}\n")
        assertTrue("id=\"evidence-retry\"" in source.resolve("probe.html").readText())
        runNode(root, """
            const status = { textContent: '' };
            const retry = { hidden: true, onclick: null };
            globalThis.document = {
              getElementById(id) {
                if (id === 'evidence-status') return status;
                if (id === 'evidence-retry') return retry;
                throw new Error(`unexpected element: ${'$'}{id}`);
              },
            };
            const { createEvidenceSubmission } = await import('${source.resolve("probe-ui.js").toUri()}');
            const bodies = [];
            const submission = createEvidenceSubmission({
              catalogSha256: '${"a".repeat(64)}',
              caseIds: ['one'],
              groupId: 'base-runtime',
              fetchImpl: async (_url, options) => {
                bodies.push(options.body);
                if (bodies.length === 1) throw new Error('response lost after storage');
                return { status: 200 };
              },
            });
            await submission.recordResult({
              caseId: 'one', context: 'extension_page', detail: { kind: 'exposed' }, durationMs: 0,
              member: 'getURL', namespace: 'runtime', runToken: 'run-token-00000001', status: 'EXPOSED',
            });
            if (retry.hidden || typeof retry.onclick !== 'function') throw new Error('retry button not shown');
            await retry.onclick();
            if (!retry.hidden || status.textContent !== '증거 전송: 완료') throw new Error('retry did not complete');
            if (bodies.length !== 2 || bodies[0] !== bodies[1]) throw new Error('retry changed frozen bytes');
        """.trimIndent())
    }

    private fun loadCatalog(): ApiProbeCatalog = ApiProbeCatalog.load(
        checkNotNull(javaClass.getResourceAsStream("/catalog/sbrowser-30-public-apis.json")),
    )

    private fun runNode(root: Path, script: String) {
        val file = root.resolve("test.mjs")
        Files.writeString(file, script)
        val process = ProcessBuilder("node", file.toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("probe-submission-test-")
        try { block(root) } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
