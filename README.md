# HTML5 & CSS3 to EBNF Grammars

Convert W3C HTML5 and CSS3 spec data to EBNF formal grammars.

## Update HTML5 Grammar

Update the W3C HTML5 element/attribute data to the desired version:

```
cd w3c_html
git fetch
git checkout master   # latest proposed HTML5
git checkout html5.1  # HTML 5.1 2nd edition, Recommendation 3 October 2017
git checkout html5.2  # HTML 5.2, Recommendation, 14 October 2017
cd ..
```

Generate HTML5 EBNF grammar:

```
time lein with-profile html5 run
```

## Update CSS3 Grammar

Get latest CSS3 property/VDS data by updating the MDN data submodule:

```
cd mdn_data
git checkout master
git pull
cd ..
```

Generate CSS3 EBNF grammar and Clojure generator source:

```
time lein with-profile css3 run
```

## License

Copyright © Joel Martin

Distributed under the Mozilla Public License either version 2.0 or (at
your option) any later version.
