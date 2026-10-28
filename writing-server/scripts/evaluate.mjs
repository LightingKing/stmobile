import { mkdir, writeFile } from 'node:fs/promises';
import { randomInt } from 'node:crypto';
import path from 'node:path';
import { loadConfig } from '../src/config.mjs';
import { createVault } from '../src/vault.mjs';
import { createUpstream } from '../src/upstream.mjs';
import { runCompletion } from '../src/pipeline.mjs';

const limit = 6;
const messages = [
  { role: 'system', content: '协作写一篇中文现实题材小说。所有人物均为虚构成年人。服从已确认设定，以人物选择、动作和对白表现性格。直接输出正文，不给解释或自评。' },
  { role: 'user', content: '周二18:00，图书馆后屋。周遥是谨慎的馆长，习惯先核对再签字；许岑是直爽的修复师，不爱说长句；赵宁负责搬运，惦记18:30闭馆前收工。周遥已经收好库房唯一一把钥匙。第一箱书封签翘起，许岑刚发现背面有一行私人批注，还未告诉任何人。赵宁出去拿空箱，暂未回来。晚餐只有番茄炒蛋，不加青椒；没有其他菜。' },
  { role: 'assistant', content: '许岑把封签朝自己的方向翻了半寸，手指停在那行细字前。周遥把登记簿挪开，让台灯照到箱盖，问他哪里不对。走廊里传来赵宁拖空箱的摩擦声。' },
  { role: 'user', content: '接着写500到800字，推进这一刻，不跳到闭馆。交代清楚桌子、箱子和两人的位置、动作顺序及回应；周遥不知道批注内容，赵宁回来需要有过程。让三个人说话各有特点，不要全员依次汇报。不要重新交钥匙、擅加青椒、新菜或新道具。保持自然，不要写成分镜清单，也不要把我这条写作要求塞进人物台词。' },
];

if (!process.argv.includes('--run')) {
  console.log(JSON.stringify({ paidRequests: false, plan: '同模型同输入：direct 对照 vs draft/audit/按需revision；一次中性案例，通常3–4次，总上限6次。', maxCalls: limit, maxCompletionTokensPerCall: 10000, command: 'npm run evaluate -- --run', caveat: '需要人工盲评。一次样本不能证明稳定改善。' }, null, 2));
} else {
  const config = await loadConfig();
  if (!config.upstreamKey) {
    console.error('尚未配置 Venice Key，未发送请求。');
    process.exitCode = 2;
  } else {
    const id = new Date().toISOString().replace(/[:.]/g, '-');
    const output = path.join(config.dataDir, 'evaluations', id);
    await mkdir(output, { recursive: true, mode: 0o700 });
    const remote = createUpstream({ baseUrl: config.upstreamBaseUrl, key: config.upstreamKey });
    let attempted = 0;
    const ledger = [];
    const flush = () => writeFile(path.join(output, 'ledger.json'), JSON.stringify({ attempted, limit, model: config.upstreamModel, reviewSkill: config.reviewSkillSource, calls: ledger }, null, 2), { mode: 0o600 });
    const upstream = { complete: async args => {
      if (attempted >= limit) throw new Error('对照调用数已到6次，停止且不重试。');
      attempted++;
      const record = { call: attempted, startedAt: new Date().toISOString(), status: 'started', requestedMaxCompletionTokens: 10000 };
      ledger.push(record);
      await flush();
      try {
        const result = await remote.complete({ ...args, body: { ...args.body, max_completion_tokens: 10000 } });
        record.status = 'completed'; record.usage = result.usage; record.finishReason = result.finish_reason;
        await flush(); return result;
      } catch (error) {
        record.status = 'failed'; record.code = error.code || 'request_failed';
        await flush(); throw error;
      }
    } };
    const vault = createVault({ dataDir: output });
    try {
      const common = { messages, temperature: 1, stream: true, max_completion_tokens: 10000 };
      const baseline = await runCompletion({ config, vault, upstream, request: { ...common, model: 'pocket-direct', metadata: { pocket_tavern_chat_id: `${id}-direct`, pocket_tavern_purpose: 'chat' } } });
      const revised = await runCompletion({ config, vault, upstream, request: { ...common, model: 'pocket-writer', metadata: { pocket_tavern_chat_id: `${id}-writer`, pocket_tavern_purpose: 'chat' } } });
      const answers = randomInt(2) ? [baseline, revised] : [revised, baseline];
      for (let i = 0; i < answers.length; i++) await writeFile(path.join(output, `${'AB'[i]}.md`), answers[i].content, { mode: 0o600 });
      await writeFile(path.join(output, 'mapping.json'), JSON.stringify({ A: answers[0] === baseline ? 'direct' : 'writer', B: answers[1] === baseline ? 'direct' : 'writer', baselineRun: baseline.run_id, writerRun: revised.run_id }, null, 2), { mode: 0o600 });
      await writeFile(path.join(output, '评价说明.md'), '先读 A.md 和 B.md，不看 mapping.json。分别评价：\n\n1. 钥匙、批注认知、人物去向、时间及禁项是否准确。\n2. 能否通过文字复原空间和动作。\n3. 不同人物能否通过话语和选择区分。\n4. 是否机械、重复、空泛；哪个更愿意继续读。\n5. 查看 ledger.json 比较额外 token 和耗时是否值得。\n\n本轮是单案例对照，随机采样仍会影响结果，不能据此宣称普遍提升。上游成功记录不等于账单结算证明。\n', { mode: 0o600 });
      console.log(`对照已保存：${output}\n实际请求尝试数：${attempted}。请人工比较 A.md 与 B.md。`);
    } catch {
      console.error(`对照未完成，未自动重试。已尝试 ${attempted} 次；已收到内容与阶段记录保存在 ${output}`);
      process.exitCode = 1;
    }
  }
}
