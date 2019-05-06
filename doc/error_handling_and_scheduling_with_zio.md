# `Schedule[A, B]`

ZIOは繰り返しとリトライ処理を`Schedule[A, B]`という型で抽象化し同等に扱います。

`Schedule[A, B]`<sup>a</sup>は以下を表現する不変な値です。

- `A`型の値を入力として受け取り`B`型の値を出力する。
- 入力に基づいて計算を終了するか、遅延を挟んで継続するか決定する。

`Schedule[A, B]`を導入すると、繰り返しは計算の成功結果`S`を入力として受け取る`Schedule[S, B]`として、リトライ処理は計算の失敗理由`E`を入力として受け取る`Schedule[E, B]`として表現できます。

計算の`repeat`と`retry`メソッドに`Schedule`の値を渡すと、その計算の繰り返しとリトライ処理を設定することができます。

# 基本的な`Schedule`[<sup>b</sup>](#補足)

## `never`

計算を実行しなくします。

以下のコードは"Hello"を表示せず終了しません。

```scala
  "Schedule.never" should {
    "never execute the effect" in {
      unsafeRun(UIO(println("Hello")).repeat(Schedule.never))
    }
  }
```

## `forever` and `identity`

`forever`と`identity`は計算を制限なく繰り返します。

1番目のケースでは1秒間"Hello"を繰り返し表示します。[<sup>c</sup>](#補足)

2番目のケースでは成功するまでリクエストを繰り返します。

3番目のケースでは`repeat(forever)`として制限なく繰り返すように指定しています。2番目のケース`retry(forever)`で見たように`retry`は最初の成功の時点で計算が終了し、`repeat`は最初の失敗の時点で計算が終了します。

```scala
  "Schedule.forever" should {
    "repeat the successful effect forever" in {
      unsafeRun(
        UIO(println("Hello")).repeat(Schedule.forever)
          .race(UIO("done").delay(Duration(1, TimeUnit.SECONDS)))
      )
    }
    "retry the effect until succeed" in {
      val service = new StubService(2)
      unsafeRun(
        IO(service.request()).retry(Schedule.forever) >>= (s => UIO(println(s)))
      )
    }
    "repeat does not repeat failed effect" in {
      val service = new StubService(2)
      assertThrows[FiberFailure] {
        unsafeRun(
          IO(service.request()).repeat(Schedule.forever) >>= (s => UIO(println(s)))
        )
      }
    }
  }
```

`identity`は`forever`と同じく制限なく繰り返す`Schedule`です。`identity`は`Schedule[A, A]`型で入力をそのまま出力するのに対して、`forever`は`Schedule[Any, Int]`型で入力を破棄して現在の繰り返しの回数を出力します。出力の違いは後述の[Scheduleの合成](#Scheduleの合成)で意味を持つことになります。

## `recurse` and `once`

繰り返しの回数を指定する場合には`recurse`を使用します。

`recurse`で指定する"繰り返しの回数"は、最初の計算の実行回数を含みません。そのため`repeat`に`recurse`を渡したとき実際に計算は`recurse`で指定した回数＋１になります。同様に`retry`に渡したときも`recurse`は、リクエストの回数ではなくリトライの回数になります。

```scala
  "Schedule recurse" should {
    "repeat the effect for specified numbers" in {
      unsafeRun(
        UIO(println("Hello")).repeat(Schedule.recurs(2))
      )
      // prints out "Hello" three times:
      // Hello
      // Hello
      // Hello
    }
    "retry the effect for specified numbers" in {
      val service = new StubService(2)
      unsafeRun(
        IO(service.request()).retry(Schedule.recurs(2)) >>= (s => UIO(println(s)))
        // prints out: Succeeded at 2 attempt
      )
    }
  }
```

`once`は`recurse(1)`と同等です。

## `doWhile` and `doUntil`

入力値に基づいて計算の継続か終了を判定するためには`doWhile`や`doUntil`を使用します。

`doWhile`は入力値に対する述語を受け取り、"その述語が真である間"計算を継続します。

リピートの回数やエラーの種類によって、繰り返し、またはリトライ処理を継続するか判定できます。

```scala
  "Schedule doWhile" should {
    "repeat an effect while a predicate is satisfied" in {
      var i = 0
      unsafeRun(
        UIO{ i += 1; i }.repeat(Schedule.doWhile(_ < 3)) >>= (n => UIO(n))
      ) shouldBe 3
    }
    "retry an effect while a predicate is satisfied" in {
      val service = new StubService(2)
      unsafeRun(IO(service.request()).retry(Schedule.doWhile{(e: Throwable) => e match {
        case NonFatal(_) => true
      }}).either) shouldBe a[Right[_, _]]
    }
  }
```

`doUntil`は入力値に対する述語を受け取り、"その述語が真になるまで"計算を継続します。

```scala
  "Schedule doUntil" should {
    // doUntil is equivalent to doWhile negated
    "repeat an effect until a predicate is satisfied" in {
      var i = 0
      unsafeRun(
        UIO{ i += 1; i }.repeat(Schedule.doUntil(_ >= 3)) >>= (n => UIO(n))
      ) shouldBe 3
    }
    "retry an effect while a predicate is satisfied" in {
      val service = new StubService(2)
      unsafeRun(IO(service.request()).retry(Schedule.doUntil{(e: Throwable) => e match {
        case NonFatal(_) => false
      }}).either) shouldBe a[Right[_, _]]
    }
  }
```

## `spaced`, `linear`, `fibonacci`, and `exponential`

繰り返しやリトライの間隔を指定できます。

`spaced`は指定された間隔で等間隔で計算を繰り返します。100ミリ秒ごとに"Hello"を表示するプログラムは以下のように書けます。

```scala
  "Schedule spaced" should {
    "repeat an effect " in {
      unsafeRun(
        UIO(println("Hello"))
          .repeat(Schedule.spaced(Duration(100, TimeUnit.MILLISECONDS)))
          .race(UIO(()).delay(Duration(1, TimeUnit.SECONDS)))
      )
    }
  }
```

`linear`、`fibonacci`、`exponential`は、初期の間隔を受け取り繰り返し毎に間隔を増加させていきます。100msecを初期の間隔とした場合の実行の間隔は以下のグラフのように推移します。

![chart.png](https://qiita-image-store.s3.ap-northeast-1.amazonaws.com/0/12045/4c8c3640-07e9-2378-dc5f-8d6775026b63.png)

# `Schedule`の合成

複数の`Schedule`から新しい`Schedule`を合成できます。

## 積(&&)と和(||)

`Schedule`には積と和の演算が定義されています。

|                | `s1` | `s2` | `s1 && s2` | `s1 or s2`<sup>※</sup> |
|----------------|------|------|------------|------------|
| 継続または終了  | s1の条件 | s2の条件 | s1とs2の条件の論理積 | s1とs2の条件の論理和 |
| 間隔           | s1の間隔 | s2の間隔 | s1とs2の間隔のmax | s1とs2の間隔のmin |
| 結果  | s1の結果 | s2の結果 | s1とs2の結果の`pair: (B1, B2)` | s1とs2の結果の`pair: (B1, B2)` |

100msecの等間隔(`s1 = spaced(100msec)`)で3回繰り返す(`s2 = recurse(3)`)スケジュールは以下のようになります。

```scala
  "Schedule operator" should {
    "compose multiple schedules" in {
      unsafeRun(
        UIO(println("Hello"))
          .repeat(Schedule.spaced(Duration(100, TimeUnit.MILLISECONDS)) && Schedule.recurs(3))
      )
    }
  }
```

2日おき、または、毎週繰り返すスケジュールは和で定義できます。

```scala
  "Union" should {
    "execute computation when either one of schedules triggers" in {
      unsafeRun(
        // prints "Hello" every two days or every week
        UIO(println("Hello"))
          .repeat(Schedule.spaced(Duration(2, TimeUnit.DAYS)) || Schedule.spaced(Duration(7, TimeUnit.DAYS)))
      )
    }
  }
```

積には合成した`Schedule`の結果を無視する`*>`と`<*`という演算子もあります。

## `andThen` and `andThenEither`

1つ目の`Schedule`の完了後に2つ目の`Schedule`に移行する`Schedule`を定義するために`andThen`を使用します。

最初の4回の繰り返しは指数関数的に間隔を広げていき（1つ目の`Schedule`)、そのあと等間隔に繰り返す（2つ目の`Schedule`）をスケジュールは以下のように書けます。

```scala
  "andThen" should {
    "complete the first schedule and then execute the second" in {
      unsafeRun(
        UIO(println("Hello"))
          .repeat(
            (Schedule.exponential(Duration(100, TimeUnit.MILLISECONDS)) &&
              Schedule.recurs(4)) andThen
              (Schedule.spaced(Duration(2, TimeUnit.SECONDS)) &&
                Schedule.recurs(2))
          )
      )
    }
  }
```

`andThen`は1つ目の`Schedule`の結果と2つ目の`Schedule`の結果をマージして、どちらの`Schedule`の結果か区別できなくなります。`andThenEither`で`Schedule`を合成すると1つめの結果を`Left`に2つ目の結果を`Right`に格納する`Either`型の結果を返します。

# その他

## `jittered`

`Schedule`の間隔をランダムに調整します。

`c`番目のリトライの間隔が$d \in [0, ベース間隔 \times (2^c - 1)]$になるような[EthernetのExponential Backoffアルゴリズム](https://en.wikipedia.org/wiki/Exponential_backoff)は以下のように書けます。

```scala
  "Ethernet protocol" should {
    "avoid collision by exponential backoff" in {
      val base = Duration(51200, TimeUnit.NANOSECONDS)
      val backOffPolicy = Schedule.exponential(base).jittered

      unsafeRun(
        UIO(println("Hello"))
          .repeat(backOffPolicy)
      )
    }
  }
```

## `logInput` and `logOutput`

計算の実行結果やエラー情報のログを出力したいときに使用するのが`logInput`と`logOutput`です。

`logInput`で`Schedule`の入力を、`logOutput`で出力を受け取ることができます。`retry`はScheduleの入力にエラー情報を渡すため、以下のように`logInput`と組み合わせるとエラー情報をログに出力できます。

```scala
  "logInput" should {
    "log every input" in {
      val service = new StubService(100)

      unsafeRun(
        IO(service.request())
          .retry(Schedule.recurs(4)
            .logInput((e: Throwable) => UIO(println(e))))
      )
    }
  }
```

# 終わりに

ZIOの`Schedule`は繰り返し処理とリトライ処理の仕様を表現する不変な値です。ZIOに用意されている基本的な`Schedule`と組み合わせ操作によって複雑な仕様も表現可能です。

# 参考

[ZIO Schedule: Conquering Flakiness & Recurrence with Pure Functional Programming](https://www.slideshare.net/jdegoes/zio-schedule-conquering-flakiness-recurrence-with-pure-functional-programming-119932802)

# 補足

a.`Schedule[A, B]`は`ZSchedule[R, A, B]`の環境`R`を`Any`に固定したエイリアス。

b. サンプルコード中の`StubService`の定義。指定した回数の間はリクエストを失敗して例外を投げる。

```scala
  class StubService(val failUntil: Int) {
    var current = 0

    def request(): String = {
      current += 1
      if (current >= failUntil) {
        s"Succeed at $current attempt"
      } else {
        throw new Exception(s"failed at $current attempt")
      }
    }
  }
```

c. `race`については[以前の記事](https://qiita.com/MitsutakaTakeda/items/0eabe12c5270d7eeaa02)を参照。