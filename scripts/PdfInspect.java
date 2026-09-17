import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * PDF 报告体检探针 —— 把导出的 PDF 拆开看三件事。
 *
 * <p>为什么需要它：PDF 的中文是否真的嵌入了字体、字形是否真的画得出来，
 * <b>光看字节数和 HTTP 200 是判断不了的</b>。一份「中文全是方框」的 PDF 同样是
 * 200 且体积正常。必须拆开看内容与字体。
 *
 * <ol>
 *   <li><b>提取文本</b> —— 验证 ToUnicode 映射对不对。中文若是方框，这里会出来
 *       一堆 U+FFFD 或空字符串。</li>
 *   <li><b>列出嵌入字体</b> —— 确认用的是我们指定的 CJK 字体且 {@code 嵌入=是}
 *       （子集化的字体名形如 {@code AOTLCF+SimHei}）。</li>
 *   <li><b>栅格化某一页为 PNG</b> —— 最后一道确认：提取文本正确但字形缺失是可能的，
 *       只有看图能排除。</li>
 * </ol>
 *
 * <p>用法（单文件源码模式，无需编译）：
 * <pre>
 *   java -cp "target/classes;$(cat target/cp.txt)" scripts/PdfInspect.java \
 *        report.pdf page1.png [提取页数=2] [渲染页索引(0-based)=0]
 * </pre>
 *
 * <p>classpath 由 {@code mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt} 生成。
 * PDFBox 是 openhtmltopdf 的传递依赖，已在 classpath 上。
 */
public class PdfInspect {
    public static void main(String[] args) throws Exception {
        File pdf = new File(args[0]);
        File png = new File(args.length > 1 ? args[1] : "page1.png");
        int maxPages = args.length > 2 ? Integer.parseInt(args[2]) : 2;

        try (PDDocument doc = PDDocument.load(pdf)) {
            System.out.println("页数: " + doc.getNumberOfPages());

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(maxPages, doc.getNumberOfPages()));
            String text = stripper.getText(doc);

            long cjk = text.chars().filter(c -> c >= 0x4e00 && c <= 0x9fff).count();
            long bad = text.chars().filter(c -> c == 0xFFFD).count();
            System.out.println("提取字符数: " + text.length()
                    + " | 中日韩汉字: " + cjk
                    + " | 替换字符(U+FFFD): " + bad);

            System.out.println("---- 提取文本前 1200 字 ----");
            System.out.println(text.substring(0, Math.min(1200, text.length())));
            System.out.println("---- 提取文本结束 ----");

            Set<String> fonts = new LinkedHashSet<>();
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) continue;
                for (var name : res.getFontNames()) {
                    PDFont f = res.getFont(name);
                    if (f == null) continue;
                    fonts.add(f.getName() + "  [嵌入=" + (f.isEmbedded() ? "是" : "否") + "]");
                }
            }
            System.out.println("---- 嵌入字体 ----");
            fonts.forEach(s -> System.out.println("  " + s));

            PDFRenderer renderer = new PDFRenderer(doc);
            int pageIdx = args.length > 3 ? Integer.parseInt(args[3]) : 0;
            BufferedImage img = renderer.renderImageWithDPI(pageIdx, 110);
            ImageIO.write(img, "png", png);
            System.out.println("已渲染第 " + (pageIdx + 1) + " 页 -> " + png.getAbsolutePath()
                    + " (" + img.getWidth() + "x" + img.getHeight() + ")");
        }
    }
}
