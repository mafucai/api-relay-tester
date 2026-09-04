package com.mafucai.relayscope;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative parser: it produces candidates, never silently overwrites saved prices. */
public final class PriceOcrParser {
    public static final class Candidate {
        public String model = "", input = "", output = "", multiplier = "";
        public boolean inputCertain, outputCertain, multiplierCertain;
        public String missing() { StringBuilder b=new StringBuilder(); if(model.isEmpty())b.append("模型名、"); if(input.isEmpty())b.append("输入价格、"); if(output.isEmpty())b.append("输出价格、"); if(multiplier.isEmpty())b.append("余额扣费倍率、"); return b.length()==0?"":b.substring(0,b.length()-1); }
    }
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])(?:\\d+(?:[.,]\\d+)?)(?![A-Za-z])");
    private static final Pattern MODEL = Pattern.compile("(?i)(?:gpt[-_\\w.]*|claude[-_\\w.]*|deepseek[-_\\w.]*|gemini[-_\\w.]*|qwen[-_\\w.]*|豆包[-_\\w.]*|通义[-_\\w.]*)");
    private PriceOcrParser() {}
    public static Candidate parse(String text) {
        Candidate c=new Candidate(); String value=text==null?"":text.replace('，',',').replace('：',':');
        Matcher model=MODEL.matcher(value);if(model.find())c.model=model.group();
        c.input=near(value,"输入|input|prompt");c.output=near(value,"输出|output|completion");c.multiplier=near(value,"倍率|倍数|multiplier|x");
        c.inputCertain=!c.input.isEmpty();c.outputCertain=!c.output.isEmpty();c.multiplierCertain=!c.multiplier.isEmpty();
        return c;
    }
    private static String near(String text,String labels){Matcher label=Pattern.compile("(?i)("+labels+")[^\\d]{0,24}([0-9]+(?:[.,][0-9]+)?)").matcher(text);return label.find()?label.group(2).replace(',','.'):"";}
    public static String format(Candidate c){return String.format(Locale.CHINA,"模型：%s\n输入价：%s / 1M\n输出价：%s / 1M\n站内余额扣费倍率：%s×",c.model,c.input,c.output,c.multiplier);}
}
