package io.github.pandong2015.ffmpeg4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.pandong2015.ffmpeg4j.model.FilterNode.Arg;

/**
 * 内部滤镜节点值对象 {@link FilterNode} 及其嵌套 {@link FilterNode.Arg} 的纯逻辑单元测试：
 * 构造/访问器、防御性拷贝与不可变、参数渲染各分支、toString 精确格式。
 */
class FilterNodeTest {

    /** 便捷取一路真实视频流作为节点输入（纯值构造，不触发 ffmpeg）。 */
    private static VideoStream aVideoInput() {
        return Input.of("in.mp4").video();
    }

    // ---------------- Arg record ----------------

    @Test
    void Arg工厂of默认不转义且非位置() {
        Arg a = Arg.of("w", "1280");
        assertEquals("w", a.key(), "key 应为传入值");
        assertEquals("1280", a.value(), "value 应为传入值");
        assertFalse(a.escape(), "of() 产出的参数默认不需转义");
        assertFalse(a.isPositional(), "带非空 key 的参数不是位置参数");
    }

    @Test
    void Arg工厂escaped标记需转义() {
        Arg a = Arg.escaped("filename", "C:/x.srt");
        assertEquals("filename", a.key());
        assertEquals("C:/x.srt", a.value());
        assertTrue(a.escape(), "escaped() 须把 escape 标记为 true 以便编译器转义自由文本/路径");
        assertFalse(a.isPositional());
    }

    @Test
    void Arg工厂positional为位置参数且key为null() {
        Arg a = Arg.positional("PTS-STARTPTS");
        assertEquals(null, a.key(), "位置参数无 key");
        assertEquals("PTS-STARTPTS", a.value());
        assertFalse(a.escape(), "positional() 默认不转义");
        assertTrue(a.isPositional(), "key 为 null 时应判为位置参数");
    }

    @Test
    void isPositional对空字符串key也返回true() {
        // key 为 null 或空串都视作位置参数（见 isPositional 实现的两个分支）
        assertTrue(Arg.of("", "v").isPositional(), "空串 key 应判为位置参数");
        assertTrue(new Arg(null, "v", false).isPositional(), "null key 应判为位置参数");
        assertFalse(Arg.of("k", "v").isPositional(), "非空 key 不是位置参数");
    }

    @Test
    void Arg记录访问器与相等性() {
        Arg a1 = new Arg("k", "v", true);
        Arg a2 = new Arg("k", "v", true);
        Arg a3 = new Arg("k", "v", false);
        // record 自动生成的 equals/hashCode 逐组件比较
        assertEquals(a1, a2, "同组件的 Arg 应相等");
        assertEquals(a1.hashCode(), a2.hashCode(), "相等对象 hashCode 应一致");
        assertNotEquals(a1, a3, "escape 不同则不相等");
    }

    // ---------------- FilterNode 构造与访问器 ----------------

    @Test
    void 构造器访问器原样返回各字段() {
        VideoStream in = aVideoInput();
        List<Arg> args = List.of(Arg.of("w", "1280"), Arg.of("h", "720"));
        List<Stream> inputs = List.of(in);
        List<MediaType> outs = List.of(MediaType.VIDEO);
        FilterNode node = new FilterNode("scale", args, inputs, outs);

        assertEquals("scale", node.filter(), "filter() 应返回滤镜名");
        assertEquals(args, node.args(), "args() 内容应与传入一致");
        assertEquals(inputs, node.inputs(), "inputs() 内容应与传入一致");
        assertEquals(outs, node.outputTypes(), "outputTypes() 内容应与传入一致");
    }

    @Test
    void 构造器对入参列表做防御性拷贝不受后续改动影响() {
        VideoStream in = aVideoInput();
        List<Arg> mutableArgs = new ArrayList<>(List.of(Arg.of("k", "v")));
        List<Stream> mutableInputs = new ArrayList<>(List.of(in));
        List<MediaType> mutableOuts = new ArrayList<>(List.of(MediaType.VIDEO));
        FilterNode node = new FilterNode("f", mutableArgs, mutableInputs, mutableOuts);

        // 构造后改动原始列表，节点内部应因 List.copyOf 保持不变
        mutableArgs.add(Arg.of("x", "y"));
        mutableInputs.clear();
        mutableOuts.add(MediaType.AUDIO);

        assertEquals(1, node.args().size(), "防御性拷贝后新增的 arg 不应泄漏进节点");
        assertEquals(1, node.inputs().size(), "清空原列表不应影响节点 inputs");
        assertEquals(List.of(MediaType.VIDEO), node.outputTypes(), "追加的输出类型不应泄漏进节点");
        assertNotSame(mutableArgs, node.args(), "args() 不应是传入的同一实例");
    }

    @Test
    void 访问器返回的列表不可修改() {
        FilterNode node = new FilterNode("hflip", List.of(), List.of(), List.of(MediaType.VIDEO));
        // List.copyOf 产出不可变列表，写操作须抛 UnsupportedOperationException
        assertThrows(UnsupportedOperationException.class,
                () -> node.args().add(Arg.of("k", "v")), "args() 应不可修改");
        assertThrows(UnsupportedOperationException.class,
                () -> node.inputs().add(aVideoInput()), "inputs() 应不可修改");
        assertThrows(UnsupportedOperationException.class,
                () -> node.outputTypes().add(MediaType.AUDIO), "outputTypes() 应不可修改");
    }

    @Test
    void outputArity等于输出类型数量() {
        FilterNode one = new FilterNode("scale", List.of(), List.of(), List.of(MediaType.VIDEO));
        FilterNode two = new FilterNode("split", List.of(), List.of(),
                List.of(MediaType.VIDEO, MediaType.VIDEO));
        assertEquals(1, one.outputArity(), "单路输出 arity 为 1");
        assertEquals(2, two.outputArity(), "两路输出 arity 为 2");
    }

    // ---------------- renderBody 各分支 ----------------

    @Test
    void renderBody无参时仅返回滤镜名() {
        FilterNode node = new FilterNode("hflip", List.of(), List.of(), List.of(MediaType.VIDEO));
        assertEquals("hflip", node.renderBody(), "无参滤镜体应等于滤镜名本身");
    }

    @Test
    void renderBody渲染键值参数并以冒号连接() {
        FilterNode node = new FilterNode("scale",
                List.of(Arg.of("w", "1280"), Arg.of("h", "720")),
                List.of(), List.of(MediaType.VIDEO));
        assertEquals("scale=w=1280:h=720", node.renderBody(), "键值参数渲染为 key=value 且以冒号连接");
    }

    @Test
    void renderBody渲染位置参数仅取值() {
        FilterNode node = new FilterNode("setpts",
                List.of(Arg.positional("PTS-STARTPTS")),
                List.of(), List.of(MediaType.VIDEO));
        assertEquals("setpts=PTS-STARTPTS", node.renderBody(), "位置参数只渲染 value 部分");
    }

    @Test
    void renderBody混合位置与键值参数() {
        FilterNode node = new FilterNode("foo",
                List.of(Arg.of("k", "v"), Arg.positional("p")),
                List.of(), List.of(MediaType.VIDEO));
        assertEquals("foo=k=v:p", node.renderBody(), "键值与位置参数按序混合渲染");
    }

    @Test
    void renderBody对转义标记参数仍渲染键值形式() {
        // escape 标记只影响编译器是否转义 value，不改变 renderBody 的 key=value 结构
        FilterNode node = new FilterNode("subtitles",
                List.of(Arg.escaped("filename", "movie.srt")),
                List.of(), List.of(MediaType.VIDEO));
        assertEquals("subtitles=filename=movie.srt", node.renderBody(),
                "renderBody 不做转义，仅按 key=value 渲染");
    }

    // ---------------- toString 精确格式 ----------------

    @Test
    void toString包含滤镜体输入数与输出类型列表() {
        VideoStream in = aVideoInput();
        FilterNode node = new FilterNode("scale",
                List.of(Arg.of("w", "1280"), Arg.of("h", "720")),
                List.of(in), List.of(MediaType.VIDEO));
        assertEquals("FilterNode[scale=w=1280:h=720, in=1, out=[VIDEO]]", node.toString(),
                "toString 应拼接 renderBody、输入数与输出类型列表");
    }

    @Test
    void toString无输入无参时的格式() {
        FilterNode node = new FilterNode("hflip", List.of(), List.of(),
                List.of(MediaType.VIDEO, MediaType.AUDIO));
        assertEquals("FilterNode[hflip, in=0, out=[VIDEO, AUDIO]]", node.toString(),
                "无参无输入时 renderBody 为滤镜名、in 为 0、out 列出全部输出类型");
    }
}
