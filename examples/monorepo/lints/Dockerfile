FROM ruby:4.0.7-trixie
WORKDIR /lints
COPY Gemfile Gemfile.lock ./
RUN bundle install
WORKDIR /src
