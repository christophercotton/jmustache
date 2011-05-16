//
// JMustache - A Java implementation of the Mustache templating language
// http://github.com/samskivert/jmustache/blob/master/LICENSE

package com.samskivert.mustache;

import org.json.JSONArray;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Provides <a href="http://mustache.github.com/">Mustache</a> templating services.
 * <p> Basic usage: <pre>{@code
 * String source = "Hello {{arg}}!";
 * Template tmpl = Mustache.compiler().compile(source);
 * Map<String, Object> context = new HashMap<String, Object>();
 * context.put("arg", "world");
 * tmpl.execute(context); // returns "Hello world!" }</pre>
 * <p> Limitations:
 * <ul><li> Only one or two character delimiters are supported when using {{=ab cd=}} to change
 * delimiters.
 * <li> {{< include}} is not supported. We specifically do not want the complexity of handling the
 * automatic loading of dependent templates. </ul>
 */
public class Mustache
{
    /** An interface to the Mustache compilation process. See {@link Mustache}. */
    public static class Compiler
    {
        /** Whether or not HTML entities are escaped by default. */
        public final boolean escapeHTML;

        /** Whether or not standards mode is enabled. */
        public final boolean standardsMode;

        /** A value to use when a variable cannot be resolved, or resolves to null. If the default
         * value is null (which is the default default value), an exception will be thrown. */
        public final String defaultValue;

        /** The template loader in use during this compilation. */
        public final TemplateLoader loader;

        /** Compiles the supplied template into a repeatedly executable intermediate form. */
        public Template compile (String template) {
            return compile(new StringReader(template));
        }

        /** Compiles the supplied template into a repeatedly executable intermediate form. */
        public Template compile (Reader source) {
            return Mustache.compile(source, this);
        }

        /** Returns a compiler that either does or does not escape HTML by default. */
        public Compiler escapeHTML (boolean escapeHTML) {
            return new Compiler(escapeHTML, this.standardsMode, this.defaultValue, this.loader);
        }

        /** Returns a compiler that either does or does not use standards mode. Standards mode
         * disables the non-standard JMustache extensions like looking up missing names in a parent
         * context. */
        public Compiler standardsMode (boolean standardsMode) {
            return new Compiler(this.escapeHTML, standardsMode, this.defaultValue, this.loader);
        }

        /** Returns a compiler that will use the given value for any variable that is missing, or
         * otherwise resolves to null. */
        public Compiler defaultValue (String defaultValue) {
            return new Compiler(this.escapeHTML, this.standardsMode, defaultValue, this.loader);
        }

        /** Returns a compiler configured to use the supplied template loader to handle partials. */
        public Compiler withLoader (TemplateLoader loader) {
            return new Compiler(this.escapeHTML, this.standardsMode, this.defaultValue, loader);
        }

        protected Compiler (boolean escapeHTML, boolean standardsMode, String defaultValue,
                            TemplateLoader loader) {
            this.escapeHTML = escapeHTML;
            this.standardsMode = standardsMode;
            this.defaultValue = defaultValue;
            this.loader = loader;
        }
    }

    /** Used to handle partials. */
    public interface TemplateLoader
    {
        /** Returns a reader for the template with the supplied name.
         * @throws Exception if the template could not be loaded for any reason. */
        public Reader getTemplate (String name) throws Exception;
    }

    /**
     * Returns a compiler that escapes HTML by default and does not use standards mode.
     */
    public static Compiler compiler ()
    {
        return new Compiler(true, false, null, FAILING_LOADER);
    }

    /**
     * Compiles the supplied template into a repeatedly executable intermediate form.
     */
    protected static Template compile (Reader source, Compiler compiler)
    {
        // a hand-rolled parser; whee!
        Accumulator accum = new Accumulator(compiler);
        Delims delims = new Delims();
        int state = TEXT;
        StringBuilder text = new StringBuilder();
        int line = 1;
        boolean skipNewline = false;

        while (true) {
            char c;
            try {
                int v = source.read();
                if (v == -1) {
                    break;
                }
                c = (char)v;
            } catch (IOException e) {
                throw new MustacheException(e);
            }

            if (c == '\n') {
                line++;
                // if we just parsed an open section or close section task, we'll skip the first
                // newline character following it, if desired; TODO: handle CR, sigh
                if (skipNewline) {
                    skipNewline = false;
                    continue;
                }
            } else {
                skipNewline = false;
            }

            switch (state) {
            case TEXT:
                if (c == delims.start1) {
                    if (delims.start2 == NO_CHAR) {
                        accum.addTextSegment(text);
                        state = TAG;
                    } else {
                        state = MATCHING_START;
                    }
                } else {
                    text.append(c);
                }
                break;

            case MATCHING_START:
                if (c == delims.start2) {
                    accum.addTextSegment(text);
                    state = TAG;
                } else {
                    text.append(delims.start1);
                    if (c != delims.start1) {
                        text.append(c);
                        state = TEXT;
                    }
                }
                break;

            case TAG:
                if (c == delims.end1) {
                    if (delims.end2 == NO_CHAR) {
                        if (text.charAt(0) == '=') {
                            delims.updateDelims(text.substring(1, text.length()-1));
                            text.setLength(0);
                        } else {
                            sanityCheckTag(text, line, delims.start1, delims.start2);
                            accum = accum.addTagSegment(text, line);
                            skipNewline = accum.skipNewline();
                        }
                        state = TEXT;
                    } else {
                        state = MATCHING_END;
                    }
                } else {
                    text.append(c);
                }
                break;

            case MATCHING_END:
                if (c == delims.end2) {
                    if (text.charAt(0) == '=') {
                        delims.updateDelims(text.substring(1, text.length()-1));
                        text.setLength(0);
                    } else {
                        // if we haven't remapped the delimiters, and the tag starts with {{{ then
                        // require that it end with }}} and disable HTML escaping
                        if (delims.isDefault() && text.charAt(0) == delims.start1) {
                            try {
                                // we've only parsed }} at this point, so we have to slurp in
                                // another character from the input stream and check it
                                int end3 = (char)source.read();
                                if (end3 != '}') {
                                    throw new MustacheParseException(
                                        "Invalid triple-mustache tag: {{{" + text + "}}", line);
                                }
                            } catch (IOException e) {
                                throw new MustacheException(e);
                            }
                            // convert it into (equivalent) {{&text}} which addTagSegment handles
                            text.replace(0, 1, "&");
                        }
                        // process the tag between the mustaches
                        sanityCheckTag(text, line, delims.start1, delims.start2);
                        accum = accum.addTagSegment(text, line);
                        skipNewline = accum.skipNewline();
                    }
                    state = TEXT;
                } else {
                    text.append(delims.end1);
                    if (c != delims.end1) {
                        text.append(c);
                        state = TAG;
                    }
                }
                break;
            }
        }

        // accumulate any trailing text
        switch (state) {
        case TEXT:
            accum.addTextSegment(text);
            break;
        case MATCHING_START:
            text.append(delims.start1);
            accum.addTextSegment(text);
            break;
        case MATCHING_END:
            text.append(delims.end1);
            accum.addTextSegment(text);
            break;
        case TAG:
            throw new MustacheParseException("Template ended while parsing a tag: " + text);
        }

        return new Template(accum.finish(), compiler);
    }

    private Mustache () {} // no instantiateski

    protected static void sanityCheckTag (StringBuilder accum, int line, char start1, char start2)
    {
        for (int ii = 0, ll = accum.length(); ii < ll; ii++) {
            if (accum.charAt(ii) == start1) {
                if (start2 == NO_CHAR || (ii < ll-1 && accum.charAt(ii+1) == start2)) {
                    throw new MustacheParseException("Tag contains start tag delimiter, probably " +
                                                     "missing close delimiter '" + accum + "'", line);
                }
            }
        }
    }

    protected static String escapeHTML (String text)
    {
        for (String[] escape : ATTR_ESCAPES) {
            text = text.replace(escape[0], escape[1]);
        }
        return text;
    }

    protected static final int TEXT = 0;
    protected static final int MATCHING_START = 1;
    protected static final int MATCHING_END = 2;
    protected static final int TAG = 3;

    protected static class Delims {
        public char start1 = '{';
        public char start2 = '{';
        public char end1 = '}';
        public char end2 = '}';

        public boolean isDefault () {
            return start1 == '{' && start2 == '{' && end1 == '}' && end2 == '}';
        }

        public void updateDelims (String dtext) {
            String errmsg = "Invalid delimiter configuration '" + dtext + "'. Must be of the " +
                "form {{=1 2=}} or {{=12 34=}} where 1, 2, 3 and 4 are delimiter chars.";

            String[] delims = dtext.split(" ");
            if (delims.length != 2) throw new MustacheException(errmsg);

            switch (delims[0].length()) {
            case 1:
                start1 = delims[0].charAt(0);
                start2 = NO_CHAR;
                break;
            case 2:
                start1 = delims[0].charAt(0);
                start2 = delims[0].charAt(1);
                break;
            default:
                throw new MustacheException(errmsg);
            }

            switch (delims[1].length()) {
            case 1:
                end1 = delims[1].charAt(0);
                end2 = NO_CHAR;
                break;
            case 2:
                end1 = delims[1].charAt(0);
                end2 = delims[1].charAt(1);
                break;
            default:
                throw new MustacheException(errmsg);
            }
        }
    }

    protected static class Accumulator {
        public Accumulator (Compiler compiler) {
            _compiler = compiler;
        }

        public boolean skipNewline () {
            // return true if we just added a compound segment which means we're immediately
            // following the close section tag
            return (_segs.size() > 0 && _segs.get(_segs.size()-1) instanceof CompoundSegment);
        }

        public void addTextSegment (StringBuilder text) {
            if (text.length() > 0) {
                _segs.add(new StringSegment(text.toString()));
                text.setLength(0);
            }
        }

        public Accumulator addTagSegment (final StringBuilder accum, final int tagLine) {
            final Accumulator outer = this;
            String tag = accum.toString().trim();
            final String tag1 = tag.substring(1).trim();
            accum.setLength(0);

            switch (tag.charAt(0)) {
            case '#':
                requireNoNewlines(tag, tagLine);
                return new Accumulator(_compiler) {
                    @Override public boolean skipNewline () {
                        // if we just opened this section, we want to skip a newline
                        return (_segs.size() == 0) || super.skipNewline();
                    }
                    @Override public Template.Segment[] finish () {
                        throw new MustacheParseException(
                            "Section missing close tag '" + tag1 + "'", tagLine);
                    }
                    @Override protected Accumulator addCloseSectionSegment (String itag, int line) {
                        requireSameName(tag1, itag, line);
                        outer._segs.add(new SectionSegment(itag, super.finish(), tagLine));
                        return outer;
                    }
                };

            case '>':
                _segs.add(new IncludedTemplateSegment(tag1, _compiler));
                return this;

            case '^':
                requireNoNewlines(tag, tagLine);
                return new Accumulator(_compiler) {
                    @Override public boolean skipNewline () {
                        // if we just opened this section, we want to skip a newline
                        return (_segs.size() == 0) || super.skipNewline();
                    }
                    @Override public Template.Segment[] finish () {
                        throw new MustacheParseException(
                            "Inverted section missing close tag '" + tag1 + "'", tagLine);
                    }
                    @Override protected Accumulator addCloseSectionSegment (String itag, int line) {
                        requireSameName(tag1, itag, line);
                        outer._segs.add(new InvertedSectionSegment(itag, super.finish(), tagLine));
                        return outer;
                    }
                };

            case '/':
                requireNoNewlines(tag, tagLine);
                return addCloseSectionSegment(tag1, tagLine);

            case '!':
                // comment!, ignore
                return this;

            case '&':
                requireNoNewlines(tag, tagLine);
                _segs.add(new VariableSegment(tag1, false, tagLine));
                return this;

            default:
                requireNoNewlines(tag, tagLine);
                _segs.add(new VariableSegment(tag, _compiler.escapeHTML, tagLine));
                return this;
            }
        }

        public Template.Segment[] finish () {
            return _segs.toArray(new Template.Segment[_segs.size()]);
        }

        protected Accumulator addCloseSectionSegment (String tag, int line) {
            throw new MustacheParseException(
                "Section close tag with no open tag '" + tag + "'", line);
        }

        protected static void requireNoNewlines (String tag, int line) {
            if (tag.indexOf("\n") != -1 || tag.indexOf("\r") != -1) {
                throw new MustacheParseException(
                    "Invalid tag name: contains newline '" + tag + "'", line);
            }
        }

        protected static void requireSameName (String name1, String name2, int line)
        {
            if (!name1.equals(name2)) {
                throw new MustacheParseException("Section close tag with mismatched open tag '" +
                                                 name2 + "' != '" + name1 + "'", line);
            }
        }

        protected Compiler _compiler;
        protected final List<Template.Segment> _segs = new ArrayList<Template.Segment>();
    }

    /** A simple segment that reproduces a string. */
    protected static class StringSegment extends Template.Segment {
        public StringSegment (String text) {
            _text = text;
        }
        @Override public void execute (Template tmpl, Template.Context ctx, Writer out) {
            write(out, _text);
        }
        protected final String _text;
    }

    protected static class IncludedTemplateSegment extends Template.Segment {
        public IncludedTemplateSegment (final String templateName, final Compiler compiler) {
            Reader r;
            try {
                r = compiler.loader.getTemplate(templateName);
            } catch (Exception e) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException)e;
                } else {
                    throw new MustacheException("Unable to load template: " + templateName, e);
                }
            }
            _template = compiler.compile(r);
        }
        @Override public void execute (Template tmpl, Template.Context ctx, Writer out) {
            _template.execute(ctx.data, out);
        }
        protected final Template _template;
    }

    /** A helper class for named segments. */
    protected static abstract class NamedSegment extends Template.Segment {
        protected NamedSegment (String name, int line) {
            _name = name.intern();
            _line = line;
        }
        protected final String _name;
        protected final int _line;
    }

    /** A segment that substitutes the contents of a variable. */
    protected static class VariableSegment extends NamedSegment {
        public VariableSegment (String name, boolean escapeHTML, int line) {
            super(name, line);
            _escapeHTML = escapeHTML;
        }
        @Override public void execute (Template tmpl, Template.Context ctx, Writer out)  {
            Object value = tmpl.getValueOrDefault(ctx, _name, _line);
            if (value == null) {
//                throw new MustacheException(
//                    "No key, method or field with name '" + _name + "' on line " + _line);
                return;
            }
            String text = String.valueOf(value);
            write(out, _escapeHTML ? escapeHTML(text) : text);
        }
        protected boolean _escapeHTML;
    }

    /** A helper class for compound segments. */
    protected static abstract class CompoundSegment extends NamedSegment {
        protected CompoundSegment (String name, Template.Segment[] segs, int line) {
            super(name, line);
            _segs = segs;
        }
        protected void executeSegs (Template tmpl, Template.Context ctx, Writer out)  {
            for (Template.Segment seg : _segs) {
                seg.execute(tmpl, ctx, out);
            }
        }
        protected final Template.Segment[] _segs;
    }

    /** A segment that represents a section. */
    protected static class SectionSegment extends CompoundSegment {
        public SectionSegment (String name, Template.Segment[] segs, int line) {
            super(name, segs, line);
        }
        @Override public void execute (Template tmpl, Template.Context ctx, Writer out)  {
            Object value = tmpl.getValue(ctx, _name, _line);
            if (value == null) {
                return; // TODO: configurable behavior on missing values
            }
            if (value instanceof Iterable<?>) {
                value = ((Iterable<?>)value).iterator();
            }
            if (value instanceof Iterator<?>) {
                Template.Mode mode = null;
                int index = 0;
                for (Iterator<?> iter = (Iterator<?>)value; iter.hasNext(); ) {
                    Object elem = iter.next();
                    mode = (mode == null) ? Template.Mode.FIRST :
                        (iter.hasNext() ? Template.Mode.OTHER : Template.Mode.LAST);
                    executeSegs(tmpl, ctx.nest(elem, ++index, mode), out);
                }
            } else if (value instanceof Boolean) {
                if ((Boolean)value) {
                    executeSegs(tmpl, ctx, out);
                }
            } else if (value.getClass().isArray()) {
                for (int ii = 0, ll = Array.getLength(value); ii < ll; ii++) {
                    Template.Mode mode = (ii == 0) ? Template.Mode.FIRST :
                        ((ii == ll-1) ? Template.Mode.LAST : Template.Mode.OTHER);
                    executeSegs(tmpl, ctx.nest(Array.get(value, ii), ii+1, mode), out);
                }
            } else if (JSONArray.class.isAssignableFrom(value.getClass())) {
                JSONArray jsonArray = (JSONArray)value;
                for (int ii = 0, ll = jsonArray.length(); ii < ll; ii++) {
                    Template.Mode mode = (ii == 0) ? Template.Mode.FIRST :
                        ((ii == ll-1) ? Template.Mode.LAST : Template.Mode.OTHER);
                    executeSegs(tmpl, ctx.nest(jsonArray.opt(ii), ii+1, mode), out);
                }
            } else {
                executeSegs(tmpl, ctx.nest(value, 0, Template.Mode.OTHER), out);
            }
        }
    }

    /** A segment that represents an inverted section. */
    protected static class InvertedSectionSegment extends CompoundSegment {
        public InvertedSectionSegment (String name, Template.Segment[] segs, int line) {
            super(name, segs, line);
        }
        @Override public void execute (Template tmpl, Template.Context ctx, Writer out)  {
            Object value = tmpl.getValue(ctx, _name, _line);
            if (value == null) {
                executeSegs(tmpl, ctx, out); // TODO: configurable behavior on missing values
            } else if (value instanceof Iterable<?>) {
                Iterable<?> iable = (Iterable<?>)value;
                if (!iable.iterator().hasNext()) {
                    executeSegs(tmpl, ctx, out);
                }
            } else if (value instanceof Boolean) {
                if (!(Boolean)value) {
                    executeSegs(tmpl, ctx, out);
                }
            } else if (value.getClass().isArray()) {
                if (Array.getLength(value) == 0) {
                    executeSegs(tmpl, ctx, out);
                }
            } else if (value instanceof Iterator<?>) {
                Iterator<?> iter = (Iterator<?>)value;
                if (!iter.hasNext()) {
                    executeSegs(tmpl, ctx, out);
                }
            } // TODO: fail?
        }
    }

    /** Map of strings that must be replaced inside html attributes and their replacements. (They
     * need to be applied in order so amps are not double escaped.) */
    protected static final String[][] ATTR_ESCAPES = {
        { "&", "&amp;" },
        { "'", "&apos;" },
        { "\"", "&quot;" },
        { "<", "&lt;" },
        { ">", "&gt;" },
    };

    /** Used when we have only a single character delimiter. */
    protected static final char NO_CHAR = Character.MIN_VALUE;

    protected static final TemplateLoader FAILING_LOADER = new TemplateLoader() {
        public Reader getTemplate (String name) {
            throw new UnsupportedOperationException("Template loading not configured");
        }
    };
}
