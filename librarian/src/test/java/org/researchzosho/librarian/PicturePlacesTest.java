package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The places a collection looks in (0.8.0), read from answers of the shape each service gives: each picture's address, page, title,
 * the size the place claims (which only orders the fetching), its small copy, and the record a place keeps of who made it, when and under
 * what licence; a record that files a picture as made by AI; a size asked for as the place's own size filter; and the caption that names
 * a photographer and a year.
 */
class PicturePlacesTest {

    @Test void wikimediaCommonsAnswersAreReadWithTheirRecords() throws Exception {
        String json = """
                {"query":{"pages":{
                 "9":{"index":2,"title":"File:Harbour at dusk.jpg","imageinfo":[{"url":"https://upload.example/b/Harbour_at_dusk.jpg","thumburl":"https://upload.example/thumb/b/1024px.jpg","width":5000,"height":3000,
                   "extmetadata":{"Artist":{"value":"<a href=\\"//x\\">A. Photographer</a>"},"DateTimeOriginal":{"value":"1 April 1941 date QS:P571,+1941-04-01T00:00:00Z/11"},"LicenseShortName":{"value":"Public domain"},"Categories":{"value":"Harbours|1941 photographs"}}}]},
                 "4":{"index":1,"title":"File:Dragon render.png","imageinfo":[{"url":"https://upload.example/a/Dragon_render.png","thumburl":"","width":4096,"height":4096,
                   "extmetadata":{"Artist":{"value":"Someone"},"DateTimeOriginal":{"value":"2024-03-01"},"Categories":{"value":"AI-generated images of dragons|Dragons"}}}]}}}}""";
        List<PicturePlaces.Candidate> c = PicturePlaces.parseCommons(json);
        assertEquals(2, c.size());
        assertEquals("Dragon render", c.get(0).found().title(), "in the search's own order");
        assertFalse(c.get(0).record().aiSaid().isEmpty(), "a record filed under AI-generated says so");
        PicturePlaces.Candidate h = c.get(1);
        assertEquals("https://upload.example/b/Harbour_at_dusk.jpg", h.found().image()); assertEquals("https://commons.wikimedia.org/wiki/File:Harbour_at_dusk.jpg", h.found().page());
        assertEquals(5000, h.found().width()); assertEquals("A. Photographer", h.record().author()); assertEquals("1 April 1941", h.record().date(), "the record's machine form of the date is not said");
        assertTrue(h.record().dated()); assertEquals("Public domain", h.record().licence()); assertTrue(h.record().aiSaid().isEmpty());
        CollectionForm wide = new CollectionForm("harbour", "", "", 10, 3840, 2160, "landscape", "", "", "", 0, false), any = new CollectionForm("harbour", "", "", 10, 3840, 2160, "", "", "", "", 0, false);
        assertTrue(PicturePlaces.commonsUrl("harbour", 2, wide).contains("gsroffset=20") && PicturePlaces.commonsUrl("harbour", 1, wide).contains("filew%3A%3E3839+fileh%3A%3E2159"), PicturePlaces.commonsUrl("harbour", 1, wide));
        assertTrue(PicturePlaces.commonsUrl("harbour", 1, any).contains("filew%3A%3E2159+fileh%3A%3E2159"), "either way round: the short side on both");
    }

    @Test void theLibraryOfCongressOpenverseAndWallhavenAreReadAsTheyAnswer() throws Exception {
        List<PicturePlaces.Candidate> loc = PicturePlaces.parseLoc("""
                {"results":[{"title":"Harbour, 1906","date":"1906-04-18","url":"https://www.loc.gov/item/1/","contributor":["a studio"],
                 "image_url":["https://tile.example/1_150px.jpg#h=150&w=200","https://tile.example/1r.jpg#h=480&w=640","https://tile.example/1v.jpg#h=768&w=1024"]},
                 {"title":"Restricted","access_restricted":true,"image_url":["https://tile.example/2v.jpg#h=768&w=1024"]}]}""");
        assertEquals(1, loc.size(), "a restricted item is left out");
        assertEquals("https://tile.example/1v.jpg", loc.get(0).found().image()); assertEquals(1024, loc.get(0).found().width());
        assertEquals("https://tile.example/1r.jpg", loc.get(0).found().thumbnail()); assertTrue(loc.get(0).record().dated()); assertEquals("a studio", loc.get(0).record().author());
        List<PicturePlaces.Candidate> ov = PicturePlaces.parseOpenverse("""
                {"results":[{"url":"https://img.example/o.jpg","foreign_landing_url":"https://flickr.example/p/1","title":"Lighthouse","creator":"someone","license":"by","license_version":"2.0","provider":"flickr","width":2048,"height":1365,"thumbnail":"https://api.example/thumb/1/"}]}""");
        assertEquals("BY 2.0", ov.get(0).record().licence()); assertEquals("Openverse (flickr)", ov.get(0).found().source()); assertFalse(ov.get(0).record().dated(), "no date of making: not a record that shows who made it when");
        List<PicturePlaces.Candidate> wh = PicturePlaces.parseWallhaven("""
                {"data":[{"url":"https://wh.example/w/abc","path":"https://w.wh.example/full/abc.jpg","purity":"sfw","dimension_x":3840,"dimension_y":2160,"thumbs":{"large":"https://th.example/abc.jpg"}},
                         {"url":"https://wh.example/w/def","path":"https://w.wh.example/full/def.jpg","purity":"sketchy","dimension_x":3840,"dimension_y":2160}]}""");
        assertEquals(1, wh.size(), "family-safe only"); assertEquals(3840, wh.get(0).found().width()); assertEquals("https://th.example/abc.jpg", wh.get(0).found().thumbnail());
    }

    @Test void aCaptionNamingAPhotographerAndAYearBefore2022IsEvidence() {
        assertTrue(PicturePlaces.photographerAndYear("The old harbour. Photo by Jane Rowe, 1957.").contains("Jane Rowe"));
        assertTrue(PicturePlaces.photographerAndYear("Photographer: Max Weber (1931)").contains("1931"));
        assertEquals("", PicturePlaces.photographerAndYear("Photo by Jane Rowe, 2024."), "a year after generators came is no evidence");
        assertEquals("", PicturePlaces.photographerAndYear("The old harbour in 1957"), "a year with no one named");
        assertEquals("", PicturePlaces.photographerAndYear("Photo by night"), "no one named");
        assertEquals(1906, PicturePlaces.year("18 April 1906")); assertEquals(0, PicturePlaces.year("undated"));
    }
}
