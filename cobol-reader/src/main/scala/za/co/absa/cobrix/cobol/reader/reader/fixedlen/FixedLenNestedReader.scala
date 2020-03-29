/*
 * Copyright 2018 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.cobrix.cobol.reader.reader.fixedlen

import java.nio.charset.{Charset, StandardCharsets}

import za.co.absa.cobrix.cobol.parser.decoders.FloatingPointFormat.FloatingPointFormat
import za.co.absa.cobrix.cobol.parser.encoding.codepage.CodePage
import za.co.absa.cobrix.cobol.parser.encoding.{ASCII, EBCDIC}
import za.co.absa.cobrix.cobol.parser.policies.StringTrimmingPolicy.StringTrimmingPolicy
import za.co.absa.cobrix.cobol.parser.{Copybook, CopybookParser}
import za.co.absa.cobrix.cobol.reader.RowType
import za.co.absa.cobrix.cobol.reader.SchemaRetentionPolicy.SchemaRetentionPolicy
import za.co.absa.cobrix.cobol.reader.reader.fixedlen.iterator.FixedLenNestedRowIterator
import za.co.absa.cobrix.cobol.reader.reader.parameters.ReaderParameters
import za.co.absa.cobrix.cobol.reader.schema.CobolSchema

import scala.collection.immutable.HashMap
import scala.reflect.ClassTag

/**
  *  The Cobol data reader that produces nested structure schema
  *
  * @param copyBookContents    A copybook contents.
  * @param startOffset         Specifies the number of bytes at the beginning of each record that can be ignored.
  * @param endOffset           Specifies the number of bytes at the end of each record that can be ignored.
  * @param schemaRetentionPolicy              Specifies a policy to transform the input schema. The default policy is to keep the schema exactly as it is in the copybook.
  */
class FixedLenNestedReader[T <: RowType[T] : ClassTag](
                                  copyBookContents: Seq[String],
                                 isEbcdic: Boolean = true,
                                 ebcdicCodePage: CodePage,
                                 floatingPointFormat: FloatingPointFormat,
                                 startOffset: Int = 0,
                                 endOffset: Int = 0,
                                 schemaRetentionPolicy: SchemaRetentionPolicy,
                                 stringTrimmingPolicy: StringTrimmingPolicy,
                                 dropGroupFillers: Boolean,
                                 nonTerminals: Seq[String],
                                 occursMappings: Map[String, Map[String, Int]],
                                 readerProperties: ReaderParameters,
                                 rowCreate: Array[Any] => T
                                 )
  extends FixedLenReader with Serializable {

  protected val cobolSchema: CobolSchema = loadCopyBook(copyBookContents)

  override def getCobolSchema: CobolSchema = cobolSchema

  @throws(classOf[Exception])
  protected def getRecordIterator(binaryData: Array[Byte]): Iterator[Seq[Any]] = {
    checkBinaryDataValidity(binaryData)
    new FixedLenNestedRowIterator(binaryData, cobolSchema, readerProperties, schemaRetentionPolicy, startOffset, endOffset, rowCreate = rowCreate)
  }

  @throws(classOf[IllegalArgumentException])
  protected def checkBinaryDataValidity(binaryData: Array[Byte]): Unit = {
    if (startOffset < 0) {
      throw new IllegalArgumentException(s"Invalid record start offset = $startOffset. A record start offset cannot be negative.")
    }
    if (endOffset < 0) {
      throw new IllegalArgumentException(s"Invalid record end offset = $endOffset. A record end offset cannot be negative.")
    }
    if (binaryData.length < getExpectedLength) {
      throw new IllegalArgumentException(s"Binary record too small. Expected binary record size = $getExpectedLength, got ${binaryData.length} ")
    }
    if (binaryData.length % getExpectedLength > 0) {
      throw new IllegalArgumentException(s"Binary record size $getExpectedLength does not divide data size ${binaryData.length}.")
    }
  }

  private def getExpectedLength: Int = {
    cobolSchema.getRecordSize + startOffset + endOffset
  }

  private def loadCopyBook(copyBookContents: Seq[String]): CobolSchema = {
    val encoding = if (isEbcdic) EBCDIC else ASCII
    val segmentRedefines = readerProperties.multisegment.map(r => r.segmentIdRedefineMap.values.toList.distinct).getOrElse(Nil)
    val fieldParentMap = readerProperties.multisegment.map(r => r.fieldParentMap).getOrElse(HashMap[String,String]())
    val asciiCharset = if (readerProperties.asciiCharset.isEmpty) StandardCharsets.US_ASCII else Charset.forName(readerProperties.asciiCharset)

    val schema = if (copyBookContents.size == 1)
      CopybookParser.parseTree(encoding,
        copyBookContents.head,
        dropGroupFillers,
        segmentRedefines,
        fieldParentMap,
        stringTrimmingPolicy,
        readerProperties.commentPolicy,
        ebcdicCodePage,
        asciiCharset,
        readerProperties.isUtf16BigEndian,
        floatingPointFormat,
        nonTerminals,
        occursMappings,
        readerProperties.isDebug)
    else
      Copybook.merge(
        copyBookContents.map(
          CopybookParser.parseTree(encoding,
            _,
            dropGroupFillers,
            segmentRedefines,
            fieldParentMap,
            stringTrimmingPolicy,
            readerProperties.commentPolicy,
            ebcdicCodePage,
            asciiCharset,
            readerProperties.isUtf16BigEndian,
            floatingPointFormat,
            nonTerminals,
            occursMappings,
            readerProperties.isDebug)
        )
      )
    new CobolSchema(schema, schemaRetentionPolicy, "",false)
  }

  override def getRecordStartOffset: Int = startOffset

  override def getRecordEndOffset: Int = endOffset
}
