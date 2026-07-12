package io.github.pandong2015.ffmpeg4j.compiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.pandong2015.ffmpeg4j.model.FilterNode;
import io.github.pandong2015.ffmpeg4j.model.FilterNode.Arg;
import io.github.pandong2015.ffmpeg4j.model.Input;
import io.github.pandong2015.ffmpeg4j.model.MediaType;
import io.github.pandong2015.ffmpeg4j.model.Origin;
import io.github.pandong2015.ffmpeg4j.model.Output;
import io.github.pandong2015.ffmpeg4j.model.Stream;

/**
 * L2 图编译器：把不可变「流即值」引用图编译为 ffmpeg argv。
 *
 * <p>流程：收集输入与滤镜节点（DFS 后序） → 按引用标识统计每个 pad 的消费者 → 消费多于一次者
 * 自动插入 {@code split}/{@code asplit} 并重连 → 分配内部 pad 名 → 编译期校验（悬空 pad、字幕流扇出）
 * → 渲染 {@code -filter_complex}（含转义）+ {@code -map} + 输出参数。
 *
 * <p>去重按<em>引用标识</em>：同一 {@code Stream} 值被多次消费才复用（split）；结构相等但各自独立
 * 构造的链不会被合并（避免误并含时间戳/非确定性的滤镜）。这天然由对象图遍历 + 身份键得到。
 *
 * <p>注：ffmpeg 的 {@code -filter_complex} 由 label 全局解析，链的文本顺序不影响正确性；此处仍以
 * DFS 后序（依赖在前）产出，兼顾可读性。
 */
public final class GraphCompiler {

    private final String ffmpeg;

    public GraphCompiler() {
        this("ffmpeg");
    }

    public GraphCompiler(String ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public CompiledCommand compile(Output output) {
        return compile(List.of(output));
    }

    public CompiledCommand compile(List<Output> outputs) {
        // 1. 收集输入（首见顺序编号）与滤镜节点（后序 = 依赖在前）
        LinkedHashMap<Input, Integer> inputIndex = new LinkedHashMap<>();
        List<FilterNode> topo = new ArrayList<>();
        Set<FilterNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Output o : outputs) {
            for (Stream s : o.mapped()) {
                discover(s, inputIndex, topo, visited);
            }
        }

        // 2. 分配每个滤镜节点各路输出的 label 名（v0/a1/...）
        Map<FilterNode, String[]> nodeOutLabels = new IdentityHashMap<>();
        int[] counter = {0};
        for (FilterNode node : topo) {
            String[] outs = new String[node.outputArity()];
            for (int oi = 0; oi < outs.length; oi++) {
                outs[oi] = letterOf(node.outputTypes().get(oi)) + (counter[0]++);
            }
            nodeOutLabels.put(node, outs);
        }

        // 3. 按引用标识统计每个 pad 的消费者（滤镜输入 + 输出映射），保持确定顺序
        Map<PadKey, List<Object>> padConsumers = new LinkedHashMap<>();
        for (FilterNode node : topo) {
            List<Stream> ins = node.inputs();
            for (int i = 0; i < ins.size(); i++) {
                padConsumers.computeIfAbsent(keyOf(ins.get(i)), k -> new ArrayList<>())
                        .add(new FilterInput(node, i));
            }
        }
        for (Output o : outputs) {
            List<Stream> ms = o.mapped();
            for (int i = 0; i < ms.size(); i++) {
                padConsumers.computeIfAbsent(keyOf(ms.get(i)), k -> new ArrayList<>())
                        .add(new MapOutput(o, i));
            }
        }

        // 4. 悬空 pad 校验：每个滤镜节点的每路输出都必须被消费
        for (FilterNode node : topo) {
            for (int oi = 0; oi < node.outputArity(); oi++) {
                if (!padConsumers.containsKey(new FilterPadKey(node, oi))) {
                    throw new GraphCompileException("悬空 pad：滤镜 '" + node.filter()
                            + "' 的第 " + oi + " 路输出无下游消费者也未被映射");
                }
            }
        }

        // 5. 扇出侦测 + 自动 split；分配每个消费者的输入 label
        Map<Object, String> consumerLabel = new HashMap<>();   // token -> label 名（null=走原始 -map）
        List<String> splitChains = new ArrayList<>();
        for (Map.Entry<PadKey, List<Object>> e : padConsumers.entrySet()) {
            PadKey pk = e.getKey();
            List<Object> cons = e.getValue();
            boolean isInputPad = pk instanceof InputPadKey;
            long filterConsumers = cons.stream().filter(c -> c instanceof FilterInput).count();
            boolean needsGraphPresence = !isInputPad || filterConsumers >= 1;

            if (!needsGraphPresence) {
                // 输入 pad 且仅被映射：走原始 -map，无需 label
                for (Object c : cons) {
                    consumerLabel.put(c, null);
                }
                continue;
            }

            String producer = producerLabel(pk, inputIndex, nodeOutLabels);
            if (cons.size() == 1) {
                consumerLabel.put(cons.get(0), producer);
            } else {
                if (mediaTypeOf(pk) == MediaType.SUBTITLE) {
                    throw new GraphCompileException("字幕流不支持 filtergraph 扇出：SUBTITLE 流被消费 "
                            + cons.size() + " 次，但 ffmpeg 无字幕版 split 滤镜");
                }
                String splitName = mediaTypeOf(pk) == MediaType.AUDIO ? "asplit" : "split";
                StringBuilder chain = new StringBuilder();
                chain.append('[').append(producer).append(']').append(splitName).append('=').append(cons.size());
                for (Object c : cons) {
                    String lbl = "s" + (counter[0]++);
                    chain.append('[').append(lbl).append(']');
                    consumerLabel.put(c, lbl);
                }
                splitChains.add(chain.toString());
            }
        }

        // 6. 渲染滤镜链
        List<String> chains = new ArrayList<>();
        for (FilterNode node : topo) {
            StringBuilder sb = new StringBuilder();
            List<Stream> ins = node.inputs();
            for (int i = 0; i < ins.size(); i++) {
                sb.append('[').append(consumerLabel.get(new FilterInput(node, i))).append(']');
            }
            sb.append(renderBody(node));
            for (String out : nodeOutLabels.get(node)) {
                sb.append('[').append(out).append(']');
            }
            chains.add(sb.toString());
        }
        chains.addAll(splitChains);
        String filterComplex = chains.isEmpty() ? null : String.join(";", chains);

        // 7. 组装 argv
        List<String> argv = new ArrayList<>();
        argv.add(ffmpeg);
        argv.add("-y");
        List<Input> orderedInputs = new ArrayList<>(inputIndex.keySet());
        for (Input in : orderedInputs) {
            argv.addAll(in.inputArgs());
            argv.add("-i");
            argv.add(in.path().toString());
        }
        if (filterComplex != null) {
            argv.add("-filter_complex");
            argv.add(filterComplex);
        }
        for (Output o : outputs) {
            List<Stream> ms = o.mapped();
            for (int i = 0; i < ms.size(); i++) {
                String lbl = consumerLabel.get(new MapOutput(o, i));
                argv.add("-map");
                argv.add(lbl == null
                        ? producerLabel(keyOf(ms.get(i)), inputIndex, nodeOutLabels)  // 原始输入流说明符 0:v:0
                        : "[" + lbl + "]");
            }
            argv.addAll(o.outputArgs());
            argv.add(o.path().toString());
        }
        return new CompiledCommand(argv, filterComplex);
    }

    // ===== 内部 =====

    private void discover(Stream s, Map<Input, Integer> inputIndex, List<FilterNode> topo, Set<FilterNode> visited) {
        Origin o = s.origin();
        if (o instanceof Origin.InputOrigin io) {
            inputIndex.putIfAbsent(io.input(), inputIndex.size());
        } else if (o instanceof Origin.FilterOrigin fo) {
            FilterNode node = fo.node();
            if (!visited.add(node)) {
                return;
            }
            for (Stream in : node.inputs()) {
                discover(in, inputIndex, topo, visited);
            }
            topo.add(node);
        }
    }

    private static PadKey keyOf(Stream s) {
        Origin o = s.origin();
        if (o instanceof Origin.InputOrigin io) {
            return new InputPadKey(io.input(), io.mediaType(), io.typedIndex());
        }
        Origin.FilterOrigin fo = (Origin.FilterOrigin) o;
        return new FilterPadKey(fo.node(), fo.outputIndex());
    }

    private static MediaType mediaTypeOf(PadKey pk) {
        if (pk instanceof InputPadKey ip) {
            return ip.type();
        }
        FilterPadKey fp = (FilterPadKey) pk;
        return fp.node().outputTypes().get(fp.outputIndex());
    }

    private static String producerLabel(PadKey pk, Map<Input, Integer> inputIndex, Map<FilterNode, String[]> nodeOutLabels) {
        if (pk instanceof InputPadKey ip) {
            return inputIndex.get(ip.input()) + ":" + letterOf(ip.type()) + ":" + ip.typedIndex();
        }
        FilterPadKey fp = (FilterPadKey) pk;
        return nodeOutLabels.get(fp.node())[fp.outputIndex()];
    }

    private static String renderBody(FilterNode node) {
        if (node.args().isEmpty()) {
            return node.filter();
        }
        List<String> parts = new ArrayList<>(node.args().size());
        for (Arg a : node.args()) {
            String val = a.escape() ? Escaping.filterArgValue(a.value()) : a.value();
            parts.add(a.isPositional() ? val : a.key() + "=" + val);
        }
        return node.filter() + "=" + String.join(":", parts);
    }

    private static String letterOf(MediaType t) {
        return t.specifierLetter();
    }

    // pad 标识：输入 pad 按值（同一 0:v:0 复用），滤镜 pad 按节点身份
    private sealed interface PadKey permits InputPadKey, FilterPadKey {
    }

    private record InputPadKey(Input input, MediaType type, int typedIndex) implements PadKey {
    }

    private record FilterPadKey(FilterNode node, int outputIndex) implements PadKey {
    }

    // 消费者 token（record → 值相等，node/output 用身份相等）
    private record FilterInput(FilterNode node, int idx) {
    }

    private record MapOutput(Output output, int idx) {
    }
}
