package overflowdb.formats

import overflowdb.Graph

import java.nio.file.{Path, Paths}

trait Importer:

    def runImport(graph: Graph, inputFiles: Seq[Path]): Unit

    def runImport(graph: Graph, inputFile: Path): Unit =
        runImport(graph, Seq(inputFile))

    def runImport(graph: Graph, inputFile: String): Unit =
        runImport(graph, Paths.get(inputFile))
