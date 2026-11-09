package com.yin.ai.platform.document.model.ast;

/**
 * 公式节点 — LaTeX 数学公式，支持行内和块级。
 */
public final class FormulaNode extends DocumentNode {

    private String latex;
    private boolean inline;

    public FormulaNode() {
        super(NodeType.FORMULA);
    }

    public String getLatex() {
        return latex;
    }

    public void setLatex(String latex) {
        this.latex = latex;
    }

    public boolean isInline() {
        return inline;
    }

    public void setInline(boolean inline) {
        this.inline = inline;
    }

    @Override
    public String text() {
        return inline ? "$" + latex + "$" : "$$\n" + latex + "\n$$";
    }
}
